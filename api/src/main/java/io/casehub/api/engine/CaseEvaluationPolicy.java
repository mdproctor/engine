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
package io.casehub.api.engine;

import java.util.UUID;

/**
 * SPI for controlling how CONTEXT_CHANGED events are processed per case. Sits inside {@code
 * CaseContextChangedEventHandler} to provide per-case backpressure for the evaluate-dispatch
 * cascade.
 *
 * <p>The EventBus delivers events to all consumers unconditionally. This policy gates only the
 * binding evaluation + worker dispatch path — other CONTEXT_CHANGED consumers
 * (MilestoneLifecycleManager, blackboard layer) are unaffected.
 *
 * <p>Implementations may serialise, coalesce, gate on settlement, or bound concurrency per case.
 * Replace the default via {@code @Alternative @Priority} or Quarkus configuration.
 *
 * <p>Refs casehubio/engine#771.
 */
public interface CaseEvaluationPolicy {

  /**
   * Submit a binding evaluation for processing. The policy decides whether to run the evaluator
   * immediately, queue it, coalesce it with a pending evaluation, or gate it.
   *
   * <p>The evaluator closure captures the CONTEXT_CHANGED event and runs the current {@code
   * rules()} + {@code goals()} logic. Implementations must call {@code evaluator.run()} at least
   * once per case for each distinct context state — skipping evaluations may cause the case to
   * stall.
   *
   * <p>Called on a virtual thread. Implementations may block (virtual thread cost ≈ 0) but must not
   * hold locks across evaluator invocations in a way that prevents concurrent cases from
   * progressing.
   *
   * @param caseId the case this evaluation belongs to
   * @param evaluator runs the binding evaluation + worker dispatch
   */
  void submit(UUID caseId, Runnable evaluator);

  /**
   * Notify that all work dispatched by a prior evaluation for this case has settled. Only
   * meaningful for settlement-aware policies; the default is a no-op.
   *
   * @param caseId the case whose dispatched work has settled
   */
  default void notifySettlement(UUID caseId) {}

  /**
   * Clean up any per-case state when a case reaches a terminal status. Implementations should
   * release locks, drain queues, and remove map entries to prevent memory leaks in long-running
   * deployments.
   *
   * @param caseId the case that has terminated
   */
  default void evict(UUID caseId) {}
}
