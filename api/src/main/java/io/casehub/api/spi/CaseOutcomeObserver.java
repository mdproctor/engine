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
 * Lifecycle hook called by the engine when a case closes with a terminal outcome.
 *
 * <p>The engine discovers all {@code @ApplicationScoped CaseOutcomeObserver} beans via CDI and
 * calls {@link #onOutcome(CaseOutcomeEvent)} for each when a case reaches COMPLETED, FAULTED, or
 * CANCELLED status. Implementations write a CBR case entry to {@code CaseMemoryStore}, update trust
 * scores, or perform other outcome-based learning operations.
 *
 * <p><strong>Activation:</strong> declare an {@code @ApplicationScoped} bean implementing this
 * interface. No module dependency is required beyond {@code casehub-engine-api}. The engine
 * discovers all implementations automatically.
 *
 * <p><strong>Idempotency:</strong> the engine makes no idempotency guarantees. Implementations
 * should handle duplicate calls gracefully (e.g. using unique case IDs for dedup).
 *
 * <p><strong>Blocking:</strong> {@code onOutcome()} is called on a Vert.x worker thread (the
 * handler uses {@code blocking = true}). Implementations may perform blocking work directly,
 * including JPA writes and {@code @Transactional} operations. The engine catches and logs all
 * exceptions thrown by observers without propagating them.
 *
 * <p>Refs casehubio/engine#477 (CBR Retain step — part of casehubio/parent#227).
 */
public interface CaseOutcomeObserver {

  /**
   * Called when a case closes with a terminal outcome.
   *
   * @param event structured outcome carrying case type, context snapshot, and terminal label
   */
  void onOutcome(CaseOutcomeEvent event);
}
