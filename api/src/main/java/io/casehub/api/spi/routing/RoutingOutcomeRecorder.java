/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.api.spi.routing;

import io.smallrye.mutiny.Uni;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Optional SPI for recording routing outcomes to a persistent store, enabling feedback-loop routing
 * strategies (e.g. CBR-enriched LLM routing).
 *
 * <p>The engine calls {@code record()} after a worker completes execution — both success and
 * failure paths. PlannedAction events that require gate approval are skipped; the {@code
 * approved()} re-dispatch records on its second pass.
 *
 * <p>Implementations are discovered via {@code Instance<RoutingOutcomeRecorder>}. When no
 * implementation is present, the engine silently skips recording. Implementations must be
 * thread-safe — {@code record()} may be called concurrently.
 *
 * <p>The engine subscribes fire-and-forget — recording failure never blocks execution.
 */
public interface RoutingOutcomeRecorder {

  /**
   * Record a routing outcome.
   *
   * @param context the routing context at the time of the decision (case state before output
   *     application)
   * @param workerId the worker that was selected and executed
   * @param bindingName the case definition binding that dispatched the worker
   * @param executionOutcome the outcome string — {@code "SUCCESS"} or {@code "FAILURE"}
   * @param executionDuration wall-clock duration of the worker execution; nullable when the engine
   *     does not track dispatch timestamps
   * @return a Uni completing when the record is persisted
   */
  Uni<Void> record(
      AgentRoutingContext context,
      String workerId,
      String bindingName,
      String executionOutcome,
      @Nullable Duration executionDuration);
}
