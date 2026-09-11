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
package io.casehub.engine.common.spi;

/**
 * SPI for scheduling judgment yield requests from engine bindings.
 *
 * <p>Supports both the legacy {@link JudgmentScheduleRequest} path and the unified {@link
 * JudgmentRequest} path. New implementations should override {@link #schedule(JudgmentRequest)}.
 *
 * <p>Refs engine#996, engine#994, engine#1010.
 */
public interface JudgmentScheduler {

  /**
   * @deprecated Use {@link #schedule(JudgmentRequest)} instead.
   */
  @Deprecated(forRemoval = true)
  void schedule(JudgmentScheduleRequest request);

  default void schedule(JudgmentRequest request) {
    if (request.payload() instanceof JudgmentPayload.BindingPayload bp) {
      schedule(
          new JudgmentScheduleRequest(
              request.caseId(),
              request.tenancyId(),
              request.bindingName(),
              bp.target(),
              bp.inputData(),
              bp.resolutionTypeName(),
              bp.expiresAtDeadline(),
              bp.caseBudgetDeadline(),
              bp.resolvedTitle(),
              bp.resolvedScope(),
              bp.resolvedCandidateGroups(),
              bp.resolvedCandidateUsers(),
              bp.payloadTypeName(),
              bp.experiences(),
              bp.candidateScores()));
    }
  }
}
