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

/** Value of the `koverGateRootMode` Gradle property, which controls how a root project is gated. */
internal enum class RootMode {
  /** Detect the role from the plugins applied to the root before koverGate. */
  AUTO,

  /** The root only aggregates its subprojects' reports. */
  AGGREGATE,

  /** The root is gated as a leaf for its own classes and also aggregates its subprojects. */
  HYBRID,
}

/** The role koverGate plays in a given project. */
internal enum class ProjectRole {
  /** Gates the project's own classes only. */
  LEAF,

  /** Aggregates the subprojects' reports and has no gate of its own. */
  AGGREGATE,

  /** Gates the project's own classes and aggregates the subprojects' reports. */
  HYBRID,
}

/** Name of the Gradle property that overrides root role detection. */
internal const val ROOT_MODE_PROPERTY = "koverGateRootMode"

/** ID of the Kover Gradle plugin. */
internal const val KOVER_PLUGIN_ID = "org.jetbrains.kotlinx.kover"

/**
 * IDs of plugins that indicate a project has code of its own. `java` covers `kotlin("jvm")`, `java-library` and plain
 * Java, which all apply it.
 */
internal val CODE_PLUGIN_IDS =
  listOf(
    "java",
    "org.jetbrains.kotlin.multiplatform",
    "com.android.library",
    "com.android.application",
  )

/**
 * Parses the value of the `koverGateRootMode` property.
 *
 * @param raw the raw property value, or `null` if the property is not set
 * @return the parsed mode, case-insensitively; [RootMode.AUTO] for `null` or blank input
 * @throws GradleException if [raw] is not `auto`, `aggregate` or `hybrid`
 */
internal fun parseRootMode(raw: String?): RootMode {
  val value = raw?.trim().orEmpty()

  return if (value.isEmpty()) {
    RootMode.AUTO
  } else {
    RootMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
      ?: throw GradleException(
        "Invalid value '$raw' for the $ROOT_MODE_PROPERTY property. " +
          "Valid values are: ${RootMode.entries.joinToString { it.name.lowercase() }}."
      )
  }
}

/**
 * Decides the role koverGate takes in a project.
 *
 * @param isRootWithSubprojects `true` if the project is the root and has subprojects
 * @param mode the parsed `koverGateRootMode`; ignored unless [isRootWithSubprojects]
 * @param hasKover `true` if the Kover plugin is applied
 * @param hasCodePlugin `true` if one of [CODE_PLUGIN_IDS] is applied
 * @return the role for the project
 */
internal fun resolveProjectRole(
  isRootWithSubprojects: Boolean,
  mode: RootMode,
  hasKover: Boolean,
  hasCodePlugin: Boolean,
): ProjectRole =
  when {
    !isRootWithSubprojects -> ProjectRole.LEAF
    mode == RootMode.AGGREGATE -> ProjectRole.AGGREGATE
    mode == RootMode.HYBRID -> ProjectRole.HYBRID
    hasKover && hasCodePlugin -> ProjectRole.HYBRID
    else -> ProjectRole.AGGREGATE
  }

/**
 * Returns whether the Kover plugin is applied to [project].
 *
 * @param project the project to inspect
 * @return `true` if Kover is applied
 */
internal fun hasKover(project: Project): Boolean = project.pluginManager.hasPlugin(KOVER_PLUGIN_ID)

/**
 * Returns whether [project] applies one of [CODE_PLUGIN_IDS].
 *
 * @param project the project to inspect
 * @return `true` if a code plugin is applied
 */
internal fun hasCodePlugin(project: Project): Boolean = CODE_PLUGIN_IDS.any { project.pluginManager.hasPlugin(it) }

/**
 * Fails the build if, once the root has been evaluated, it turns out to apply both Kover and a code plugin even though
 * neither was present when this plugin was applied. Under `auto` that root was silently treated as an aggregate,
 * leaving its own classes ungated.
 *
 * @param project the root project the plugin was applied to as an aggregate
 */
internal fun registerMisorderGuard(project: Project) {
  project.afterEvaluate {
    if (hasKover(project) && hasCodePlugin(project)) {
      throw GradleException(
        "KoverGate: the root project applies Kover and a Kotlin, Java or Android plugin, but they were applied " +
          "after 'com.commonsware.kovergate', so it was treated as an aggregate-only root. List them before " +
          "'com.commonsware.kovergate' in plugins {}. Alternatively, set $ROOT_MODE_PROPERTY=hybrid to gate the " +
          "root's own code, or $ROOT_MODE_PROPERTY=aggregate to keep the aggregate only."
      )
    }
  }
}
