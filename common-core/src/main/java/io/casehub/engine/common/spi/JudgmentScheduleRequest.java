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

import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.routing.RetrievedExperience;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @deprecated Use {@link JudgmentRequest} with {@link JudgmentPayload.BindingPayload} instead.
 */
@Deprecated(forRemoval = true)
public record JudgmentScheduleRequest(
    UUID caseId,
    String tenancyId,
    String bindingName,
    JudgmentTarget target,
    Map<String, Object> inputData,
    @Nullable String resolutionTypeName,
    @Nullable Instant expiresAtDeadline,
    @Nullable Instant caseBudgetDeadline,
    @Nullable String resolvedTitle,
    @Nullable String resolvedScope,
    @Nullable Set<String> resolvedCandidateGroups,
    @Nullable Set<String> resolvedCandidateUsers,
    @Nullable String payloadTypeName,
    List<RetrievedExperience> experiences,
    Map<String, Double> candidateScores) {

  public JudgmentScheduleRequest(
      UUID caseId,
      String tenancyId,
      String bindingName,
      JudgmentTarget target,
      Map<String, Object> inputData,
      @Nullable String resolutionTypeName,
      @Nullable Instant expiresAtDeadline) {
    this(
        caseId,
        tenancyId,
        bindingName,
        target,
        inputData,
        resolutionTypeName,
        expiresAtDeadline,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        Map.of());
  }
}
