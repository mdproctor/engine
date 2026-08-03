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
package io.casehub.engine.common.spi.event;

import java.util.List;

/**
 * Routing rationale captured during agent selection.
 *
 * <p>Carries the selected candidate, alternative candidates considered, and the strategy that made
 * the selection. Used by the ledger module to write routing provenance into {@code
 * WorkerDecisionEntry}.
 *
 * @param strategyId which strategy made the selection
 * @param selected the chosen candidate
 * @param alternatives other candidates considered
 */
public record SelectionContext(
    String strategyId, SelectedCandidate selected, List<SelectedCandidate> alternatives) {

  /**
   * A candidate considered during routing.
   *
   * @param workerId the worker identifier
   * @param score the computed score for this candidate
   * @param phase the routing phase (e.g. "cbr", "workload", "trust")
   * @param reason human-readable rationale for this score
   */
  public record SelectedCandidate(String workerId, double score, String phase, String reason) {}
}
