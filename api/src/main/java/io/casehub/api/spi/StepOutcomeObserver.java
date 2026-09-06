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
package io.casehub.api.spi;

/**
 * Lifecycle hook called by the engine after each worker execution step completes.
 *
 * <p>The engine discovers all {@code @ApplicationScoped StepOutcomeObserver} beans via CDI and
 * calls {@link #onStepOutcome(StepOutcomeEvent)} for each when a worker execution finishes — on
 * both success and failure paths. Implementations record per-step CBR cases, update step-level
 * metrics, or perform other step-outcome-based learning operations.
 *
 * <p><strong>Activation:</strong> declare an {@code @ApplicationScoped} bean implementing this
 * interface. No module dependency is required beyond {@code casehub-engine-api}. The engine
 * discovers all implementations automatically.
 *
 * <p><strong>Blocking:</strong> {@code onStepOutcome()} is called on a virtual thread (the handler
 * uses {@code @RunOnVirtualThread}). Implementations may perform blocking work directly, including
 * JPA writes and {@code @Transactional} operations. The engine catches and logs all exceptions
 * thrown by observers without propagating them.
 *
 * <p>Refs casehubio/engine#1050.
 */
public interface StepOutcomeObserver {

  /**
   * Called after a worker execution step completes.
   *
   * @param event structured outcome carrying step identity, context snapshot, and outcome
   */
  void onStepOutcome(StepOutcomeEvent event);
}
