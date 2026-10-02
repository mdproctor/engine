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
 * Saga coordinator for case-level compensation. When compensation is triggered on a completed case,
 * the engine executes compensating bindings in reverse topological order.
 */
public interface CaseCompensationService {

  /**
   * Trigger compensation for a case.
   *
   * <p>Valid entry points:
   *
   * <ul>
   *   <li>{@code COMPLETED → COMPENSATING} — initial compensation
   *   <li>{@code COMPENSATION_FAULTED → COMPENSATING} — retry from faulted step
   * </ul>
   *
   * @param caseId the case to compensate
   * @param triggeredBy actor who triggered compensation (operator ID or "system")
   * @param reason human-readable reason for compensation
   * @throws IllegalStateException if case is not COMPLETED or COMPENSATION_FAULTED
   */
  void compensate(UUID caseId, String triggeredBy, String reason);
}
