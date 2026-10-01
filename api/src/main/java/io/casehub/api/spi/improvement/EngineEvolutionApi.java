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
package io.casehub.api.spi.improvement;

import io.casehub.api.model.improvement.ComplianceLevel;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ReadinessReport;
import io.casehub.api.model.stigmergy.ArtifactManifest;
import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.api.model.stigmergy.GatePolicy;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.model.stigmergy.TickTrace;
import io.casehub.api.model.stigmergy.WatchPattern;
import io.casehub.api.view.DenyPatternView;
import io.casehub.api.view.EvolutionStateSnapshot;
import io.casehub.api.view.EvolutionSummary;
import io.casehub.api.view.ResearchCorpusView;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public interface EngineEvolutionApi {

  List<TickTrace> getTickHistory(UUID caseId, @Nullable Integer limit);

  DenyPatternView getDenyPatterns(UUID caseId, String tenancyId);

  EvolutionSummary getSummary(
      UUID caseId,
      String tenancyId,
      @Nullable String areaId,
      @Nullable String category,
      @Nullable Integer timeWindowMinutes,
      @Nullable UUID improvementCaseId);

  void addDenyPattern(UUID caseId, String tenancyId, String pattern);

  void removeDenyPattern(UUID caseId, String tenancyId, String pattern);

  List<ConductorInboxEntry> getInbox(UUID caseId, String tenancyId);

  void resolveGate(
      UUID caseId,
      String tenancyId,
      String entryId,
      ConductorInboxEntry.Status outcome,
      @Nullable String reason,
      @Nullable String feedback);

  void addWatchPattern(
      UUID caseId,
      String tenancyId,
      @Nullable String category,
      @Nullable String areaId,
      @Nullable String targetPattern,
      @Nullable Integer minEstimatedSize);

  void removeWatchPattern(UUID caseId, String tenancyId, String patternId);

  void blockImprovement(UUID caseId, String tenancyId, UUID improvementId, UUID blockedBy);

  void unblockImprovement(UUID caseId, String tenancyId, UUID improvementId);

  void pauseCategory(UUID caseId, String tenancyId, String category, int durationMinutes);

  void unpauseCategory(UUID caseId, String tenancyId, String category);

  void resetCircuitBreaker(UUID caseId);

  ReadinessReport getReadinessReport(
      UUID caseId, String tenancyId, ComplianceLevel targetLevel, ImprovementConfig config);

  ReadinessReport triggerReadinessValidation(
      UUID caseId, String tenancyId, ComplianceLevel targetLevel, ImprovementConfig config);

  EvolutionStateSnapshot getEvolutionState(UUID caseId, String tenancyId, ImprovementConfig config);

  ResearchCorpusView getResearchCorpus(String query, String areaId, int limit);

  void setGatePolicy(UUID caseId, String tenancyId, GatePolicy policy);

  List<WatchPattern> getWatchPatterns(UUID caseId, String tenancyId);

  GatePolicy getGatePolicy(UUID caseId, String tenancyId);

  List<StageDescriptor> getStages(UUID caseId);

  List<CategoryDescriptor> getCategories(UUID caseId);

  ArtifactManifest getArtifactTrail(UUID caseId, String tenancyId, UUID improvementCaseId);

  List<EvolutionStateSnapshot.ImprovementStreamView> getStreamProgress(
      UUID caseId, String tenancyId);
}
