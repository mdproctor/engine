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
package io.casehub.api.view;

import io.casehub.api.model.improvement.ComplianceLevel;
import io.casehub.api.model.stigmergy.CircuitBreakerState;
import io.casehub.api.model.stigmergy.TickTrace;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EvolutionStateSnapshot(
    UUID caseId,
    Instant timestamp,
    double healthScore,
    Map<String, Double> componentScores,
    double healthDelta,
    int healthWindowMinutes,
    CircuitBreakerState circuitBreakerState,
    Map<String, CategoryStateView> categoryStates,
    ComplianceLevel projectComplianceLevel,
    @Nullable Instant complianceEvaluatedAt,
    Map<String, ComplianceLevel> areaComplianceLevels,
    List<TickTrace> recentTicks,
    int activeImprovementCount,
    int dailyImprovementCount,
    List<ImprovementStreamView> activeStreams,
    boolean evolutionEnabled,
    int pendingInboxCount) {

  public record CategoryStateView(
      int successCount,
      int failureCount,
      int rejectionCount,
      boolean paused,
      @Nullable Instant pausedUntil,
      boolean suppressed) {}

  public record ImprovementStreamView(
      UUID improvementCaseId,
      String category,
      @Nullable String target,
      String currentStage,
      List<StageProgress> stageHistory,
      @Nullable UUID blockedBy,
      boolean conflictBlocked,
      Instant startedAt) {}

  public record StageProgress(
      String stage, StageStatus status, Instant enteredAt, @Nullable Instant completedAt) {

    public enum StageStatus {
      PENDING,
      IN_PROGRESS,
      COMPLETED,
      GATED,
      SKIPPED
    }
  }
}
