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

private const val KOVER_VERSION_PROPERTY = "kovergate.test.koverVersion"

/**
 * The Kover version that functional-test fixtures apply to the projects they build. The Gradle functional-test task
 * that runs the tests supplies it through the `kovergate.test.koverVersion` system property.
 *
 * @throws IllegalStateException if the property is missing or blank, as when the tests run outside a Gradle
 *   functional-test task
 */
internal val TEST_KOVER_VERSION: String =
  checkNotNull(System.getProperty(KOVER_VERSION_PROPERTY)?.takeIf { it.isNotBlank() }) {
    "System property '$KOVER_VERSION_PROPERTY' is not set; " +
      "run the functional tests through a Gradle functional-test task"
  }
