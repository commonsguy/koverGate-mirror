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

import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult

/**
 * Build-scoped service that records the execution outcome of every task in the build and, when the build finishes, logs
 * a producer-reuse freshness warning for any module whose coverage gate ran against a Kover XML that Gradle served
 * up-to-date or from cache.
 *
 * It is registered as an [OperationCompletionListener] through `BuildEventsListenerRegistry`, which is the
 * configuration-cache-safe way to observe task outcomes. The warning is emitted from [close] — invoked once at build
 * completion — rather than from a task action, because task-finish events are delivered asynchronously and may not have
 * arrived by the time a downstream task runs; [close] is the only point at which all events are guaranteed delivered.
 */
internal abstract class KoverFreshnessService :
  BuildService<BuildServiceParameters.None>, OperationCompletionListener, AutoCloseable {
  private val outcomes = ConcurrentHashMap<String, String>()

  /**
   * Records the outcome of a finished task. Non-task events are ignored.
   *
   * @param event the build-operation finish event
   */
  override fun onFinish(event: FinishEvent) {
    if (event !is TaskFinishEvent) return

    outcomes[event.descriptor.taskPath] = labelFor(event)
  }

  private fun labelFor(event: TaskFinishEvent): String =
    when (val result = event.result) {
      is TaskFailureResult -> TaskOutcomeLabel.FAILED
      is TaskSkippedResult -> TaskOutcomeLabel.SKIPPED
      is TaskSuccessResult ->
        when {
          result.isFromCache -> TaskOutcomeLabel.FROM_CACHE
          result.isUpToDate -> TaskOutcomeLabel.UP_TO_DATE
          else -> TaskOutcomeLabel.EXECUTED
        }
      else -> TaskOutcomeLabel.UNKNOWN
    }

  /** Emits any producer-reuse freshness warnings once all task-finish events have been delivered. */
  override fun close() {
    val warnings = buildReuseWarnings(outcomes)

    for (warning in warnings) {
      logger.warn(warning)
    }
  }

  private companion object {
    private val logger = Logging.getLogger(KoverFreshnessService::class.java)
  }
}

/**
 * Registers (once per build) the [KoverFreshnessService] and subscribes it to task-completion events via
 * [listenerRegistry], so it can warn at build's end when a coverage gate ran against a Kover XML that Gradle served
 * up-to-date or from cache.
 *
 * @param project the module the plugin is applied to
 * @param listenerRegistry the Gradle registry used to subscribe the service as a task-completion listener
 */
internal fun registerFreshnessService(project: Project, listenerRegistry: BuildEventsListenerRegistry) {
  val serviceName = "koverGateFreshness"
  val alreadyRegistered = project.gradle.sharedServices.registrations.findByName(serviceName) != null
  val service = project.gradle.sharedServices.registerIfAbsent(serviceName, KoverFreshnessService::class.java) {}

  if (!alreadyRegistered) {
    listenerRegistry.onTaskCompletion(service)
  }
}
