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

/**
 * The coverage metrics that KoverGate can evaluate. Use [CoverageGateExtension.disabledMetrics] to exclude any metric
 * that does not apply to a given module (e.g., `BRANCH` for modules with no conditional logic).
 */
enum class CoverageMetric {
  LINE,
  BRANCH,
  INSTRUCTION,
  METHOD,
  CLASS,
}
