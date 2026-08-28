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
 * Classifies [file]'s gap by which of [FileCoverage.uncoveredLines] and [FileCoverage.partiallyCoveredLines] are
 * non-empty. Purely structural — no thresholds, no instruction counts.
 */
internal fun classifyGapKind(file: FileCoverage): GapKind {
  val hasCold = file.uncoveredLines.isNotEmpty()
  val hasResidual = file.partiallyCoveredLines.isNotEmpty()

  return when {
    hasCold && hasResidual -> GapKind.MIXED
    hasCold -> GapKind.COLD
    hasResidual -> GapKind.RESIDUAL
    else -> GapKind.BRANCH_ARMS_ONLY
  }
}

/**
 * Names whichever of [FileCoverage.missedInstructionsCold] or [FileCoverage.missedInstructionsResidual] is larger for
 * [file], or `null` when the two are equal (including when both are zero, i.e. a [GapKind.BRANCH_ARMS_ONLY] gap). Never
 * returns [GapKind.MIXED] or [GapKind.BRANCH_ARMS_ONLY].
 */
internal fun classifyDominantGapKind(file: FileCoverage): GapKind? =
  when {
    file.missedInstructionsCold > file.missedInstructionsResidual -> GapKind.COLD
    file.missedInstructionsResidual > file.missedInstructionsCold -> GapKind.RESIDUAL
    else -> null
  }
