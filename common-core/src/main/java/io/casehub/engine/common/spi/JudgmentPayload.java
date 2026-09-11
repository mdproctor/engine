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
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.worker.api.PlannedAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Sealed payload for unified judgment scheduling — covers both binding-level judgment yields and
 * action gate approvals.
 *
 * <p>Refs engine#1010, engine#994.
 */
public sealed interface JudgmentPayload {

  record BindingPayload(
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
      Map<String, Double> candidateScores)
      implements JudgmentPayload {

    public BindingPayload(
        JudgmentTarget target,
        Map<String, Object> inputData,
        @Nullable String resolutionTypeName,
        @Nullable Instant expiresAtDeadline) {
      this(
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

  record GatePayload(
      long gateId,
      PlannedAction plannedAction,
      RiskDecision.GateRequired gateRequired,
      Set<String> resolvedCandidateGroups,
      @Nullable String resolutionTypeName,
      @Nullable Map<String, Object> deferredOutput)
      implements JudgmentPayload {

    public GatePayload(
        long gateId,
        PlannedAction plannedAction,
        RiskDecision.GateRequired gateRequired,
        Set<String> resolvedCandidateGroups,
        @Nullable String resolutionTypeName) {
      this(gateId, plannedAction, gateRequired, resolvedCandidateGroups, resolutionTypeName, null);
    }
  }
}
