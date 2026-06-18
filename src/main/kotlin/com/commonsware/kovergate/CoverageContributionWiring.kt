/*
 * Copyright 2026 Mark L. Murphy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.commonsware.kovergate

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Wires every [CoverageGateExtension.attributeCoverageFrom] contribution: aggregates each contributing module's Kover
 * coverage into this module via the `kover` configuration, and excludes the contributors' own packages from this
 * module's report so they are not judged against this module's thresholds.
 *
 * @param project the module being gated
 * @param extension the `koverGate {}` extension holding the recorded contributions
 */
internal fun applyCoverageContributions(project: Project, extension: CoverageGateExtension) {
  val contributions = extension.coverageContributions

  if (contributions.isEmpty()) return

  if (!project.pluginManager.hasPlugin("org.jetbrains.kotlinx.kover")) {
    throw GradleException(
      "KoverGate: attributeCoverageFrom(...) requires the Kover plugin. " +
        "Apply 'org.jetbrains.kotlinx.kover' to '${project.path}'."
    )
  }

  for (contribution in contributions) {
    project.dependencies.add("kover", project.project(contribution.modulePath))
  }

  val excludedPackages = contributions.flatMap { it.excludePackages }

  if (excludedPackages.isNotEmpty()) {
    excludeKoverPackages(project, excludedPackages)
  }
}

/**
 * Reflectively adds package exclusions to Kover's report filters, equivalent to `kover { reports { filters { excludes {
 * packages(<names>) } } } }`.
 *
 * Uses reflection so KoverGate keeps no compile/runtime dependency on the Kover plugin JAR, matching
 * `isKoverUseJacocoEnabled`. Throws [GradleException] with actionable guidance if the Kover DSL shape is not recognized
 * rather than failing silently. The caller must ensure the Kover plugin is applied before calling this function;
 * [applyCoverageContributions] handles that guard.
 *
 * @param project the Gradle project whose Kover report filters are amended
 * @param packages the fully-qualified package names to exclude from this module's coverage report
 */
internal fun excludeKoverPackages(project: Project, packages: List<String>) {
  val koverExt = project.extensions.getByName("kover")

  try {
    invokeActionMethod(koverExt, "reports") { reports ->
      invokeActionMethod(reports, "filters") { filters ->
        invokeActionMethod(filters, "excludes") { excludes ->
          val packagesMethod =
            excludes.javaClass.methods.firstOrNull { it.name == "packages" && it.parameterCount == 1 }
              ?: throw NoSuchMethodException("packages(...) on ${excludes.javaClass.name}")

          if (packagesMethod.parameterTypes[0].isArray) {
            packagesMethod.invoke(excludes, packages.toTypedArray())
          } else {
            packagesMethod.invoke(excludes, packages)
          }
        }
      }
    }
  } catch (e: ReflectiveOperationException) {
    throw GradleException(
      "KoverGate: could not apply attributeCoverageFrom() package exclusions via the Kover DSL. " +
        "This Kover version may expose an incompatible reports/filters/excludes API. " +
        "Exclude the packages manually with kover { reports { filters { excludes { packages(...) } } } }.",
      e,
    )
  }
}

/**
 * Invokes a single-argument, [org.gradle.api.Action]-typed DSL method by [methodName] via reflection, passing an
 * `Action` that runs [body] against the configured object.
 *
 * @param target the receiver object exposing the method
 * @param methodName the DSL method name (e.g. `reports`, `filters`, `excludes`)
 * @param body the configuration block run against the method's configured object
 */
internal fun invokeActionMethod(target: Any, methodName: String, body: (Any) -> Unit) {
  val method =
    target.javaClass.methods.firstOrNull {
      it.name == methodName &&
        it.parameterCount == 1 &&
        org.gradle.api.Action::class.java.isAssignableFrom(it.parameterTypes[0])
    } ?: throw NoSuchMethodException("$methodName(Action) on ${target.javaClass.name}")

  val actionClass = Class.forName("org.gradle.api.Action")
  val actionImpl =
    java.lang.reflect.Proxy.newProxyInstance(actionClass.classLoader, arrayOf(actionClass)) { _, m, args ->
      if (m.name == "execute" && args.size == 1) {
        body(args[0])
        null
      } else {
        null
      }
    }

  method.invoke(target, actionImpl)
}
