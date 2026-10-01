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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.improvement.ComplianceLevel;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ReadinessReport;
import io.casehub.api.model.stigmergy.ArtifactManifest;
import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.ConductorDecision;
import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.api.model.stigmergy.GatePolicy;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.model.stigmergy.SummaryScope;
import io.casehub.api.model.stigmergy.TickTrace;
import io.casehub.api.model.stigmergy.WatchPattern;
import io.casehub.api.spi.improvement.EngineEvolutionApi;
import io.casehub.api.spi.improvement.ResearchCorpus;
import io.casehub.api.spi.improvement.SummarizationProvider;
import io.casehub.api.view.DenyPatternView;
import io.casehub.api.view.EvolutionStateSnapshot;
import io.casehub.api.view.EvolutionStateSnapshot.CategoryStateView;
import io.casehub.api.view.EvolutionSummary;
import io.casehub.api.view.ResearchCorpusView;
import io.casehub.engine.common.spi.ArtifactManifestStore;
import io.casehub.engine.common.spi.DenyPatternStore;
import io.casehub.engine.common.spi.GatePolicyStore;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DefaultEngineEvolutionApi implements EngineEvolutionApi {

  private final TickTraceBuffer tickTraceBuffer;
  private final ImprovementBudgetEnforcer budgetEnforcer;
  private final ConductorInboxManager inboxManager;
  private final SummarizationProvider summarizationProvider;
  private final ImprovementCoordinator coordinator;
  private final ImprovementCategoryTracker categoryTracker;
  private final ImprovementCircuitBreaker circuitBreaker;
  private final ReadinessValidator readinessValidator;
  private final HealthScoreTracker healthTracker;
  private final ResearchCorpus researchCorpus;
  private final GatePolicyStore gatePolicyStore;
  private final ArtifactManifestStore artifactManifestStore;
  private final DenyPatternStore denyPatternStore;
  private final ImprovementCategoryRegistry categoryRegistry;

  public DefaultEngineEvolutionApi(
      TickTraceBuffer tickTraceBuffer,
      ImprovementBudgetEnforcer budgetEnforcer,
      ConductorInboxManager inboxManager,
      SummarizationProvider summarizationProvider,
      ImprovementCoordinator coordinator,
      ImprovementCategoryTracker categoryTracker,
      ImprovementCircuitBreaker circuitBreaker,
      ReadinessValidator readinessValidator,
      HealthScoreTracker healthTracker,
      ResearchCorpus researchCorpus,
      GatePolicyStore gatePolicyStore,
      ArtifactManifestStore artifactManifestStore,
      DenyPatternStore denyPatternStore,
      ImprovementCategoryRegistry categoryRegistry) {
    this.tickTraceBuffer = tickTraceBuffer;
    this.budgetEnforcer = budgetEnforcer;
    this.inboxManager = inboxManager;
    this.summarizationProvider = summarizationProvider;
    this.coordinator = coordinator;
    this.categoryTracker = categoryTracker;
    this.circuitBreaker = circuitBreaker;
    this.readinessValidator = readinessValidator;
    this.healthTracker = healthTracker;
    this.researchCorpus = researchCorpus;
    this.gatePolicyStore = gatePolicyStore;
    this.artifactManifestStore = artifactManifestStore;
    this.denyPatternStore = denyPatternStore;
    this.categoryRegistry = categoryRegistry;
  }

  @Override
  public List<TickTrace> getTickHistory(UUID caseId, @Nullable Integer limit) {
    return tickTraceBuffer.recent(caseId, limit != null ? limit : 20);
  }

  @Override
  public DenyPatternView getDenyPatterns(UUID caseId, String tenancyId) {
    return new DenyPatternView(
        List.of(),
        denyPatternStore.findAll(caseId, tenancyId).stream()
            .map(p -> new DenyPatternView.DynamicDenyEntry(p, "operator", Instant.now()))
            .toList());
  }

  @Override
  public EvolutionSummary getSummary(
      UUID caseId,
      String tenancyId,
      @Nullable String areaId,
      @Nullable String category,
      @Nullable Integer timeWindowMinutes,
      @Nullable UUID improvementCaseId) {
    var scope = new SummaryScope(areaId, category, timeWindowMinutes, improvementCaseId);
    return summarizationProvider.summarize(caseId, tenancyId, scope);
  }

  @Override
  public void addDenyPattern(UUID caseId, String tenancyId, String pattern) {
    denyPatternStore.save(caseId, pattern, tenancyId);
  }

  @Override
  public void removeDenyPattern(UUID caseId, String tenancyId, String pattern) {
    denyPatternStore.remove(caseId, pattern, tenancyId);
  }

  @Override
  public List<ConductorInboxEntry> getInbox(UUID caseId, String tenancyId) {
    return inboxManager.pending(caseId, tenancyId);
  }

  @Override
  public void resolveGate(
      UUID caseId,
      String tenancyId,
      String entryId,
      ConductorInboxEntry.Status outcome,
      @Nullable String reason,
      @Nullable String feedback) {
    var decision = new ConductorDecision(outcome, null, reason, feedback);
    inboxManager.resolve(caseId, entryId, decision, tenancyId);
  }

  @Override
  public void addWatchPattern(
      UUID caseId,
      String tenancyId,
      @Nullable String category,
      @Nullable String areaId,
      @Nullable String targetPattern,
      @Nullable Integer minEstimatedSize) {
    var pattern =
        new WatchPattern(
            UUID.randomUUID().toString(),
            category,
            areaId,
            targetPattern,
            minEstimatedSize,
            Instant.now());
    inboxManager.addWatchPattern(caseId, pattern, tenancyId);
  }

  @Override
  public void removeWatchPattern(UUID caseId, String tenancyId, String patternId) {
    inboxManager.removeWatchPattern(caseId, patternId, tenancyId);
  }

  @Override
  public void blockImprovement(UUID caseId, String tenancyId, UUID improvementId, UUID blockedBy) {
    coordinator.block(caseId, improvementId, blockedBy, tenancyId);
  }

  @Override
  public void unblockImprovement(UUID caseId, String tenancyId, UUID improvementId) {
    coordinator.unblock(caseId, improvementId, tenancyId);
  }

  @Override
  public void pauseCategory(UUID caseId, String tenancyId, String category, int durationMinutes) {
    categoryTracker.pauseCategory(caseId, category, java.time.Duration.ofMinutes(durationMinutes));
  }

  @Override
  public void unpauseCategory(UUID caseId, String tenancyId, String category) {
    categoryTracker.unpauseCategory(caseId, category);
  }

  @Override
  public void resetCircuitBreaker(UUID caseId) {
    circuitBreaker.manualReset(caseId);
  }

  @Override
  public ReadinessReport getReadinessReport(
      UUID caseId, String tenancyId, ComplianceLevel targetLevel, ImprovementConfig config) {
    return readinessValidator.validate(caseId, tenancyId, targetLevel, config);
  }

  @Override
  public ReadinessReport triggerReadinessValidation(
      UUID caseId, String tenancyId, ComplianceLevel targetLevel, ImprovementConfig config) {
    return readinessValidator.validate(caseId, tenancyId, targetLevel, config);
  }

  @Override
  public EvolutionStateSnapshot getEvolutionState(
      UUID caseId, String tenancyId, ImprovementConfig config) {
    var latest = healthTracker.latestSnapshot(caseId);
    double healthScore = latest != null ? latest.score() : 0.0;
    Map<String, Double> componentScores = latest != null ? latest.componentScores() : Map.of();
    double healthDelta =
        healthTracker.delta(caseId, config.effectiveHealthPolicy().effectiveHealthWindowMinutes());
    int healthWindowMinutes = config.effectiveHealthPolicy().effectiveHealthWindowMinutes();
    var cbState = circuitBreaker.state(caseId);
    Map<String, CategoryStateView> categoryStates = new LinkedHashMap<>();
    for (var entry : categoryTracker.states(caseId).entrySet()) {
      var s = entry.getValue();
      categoryStates.put(
          entry.getKey(),
          new CategoryStateView(
              s.successCount(),
              s.failureCount(),
              s.rejectionCount(),
              s.paused(),
              s.pausedUntil(),
              categoryTracker.isSuppressed(caseId, entry.getKey())));
    }
    var complianceLevel = readinessValidator.cachedLevel(caseId);
    var recentTicks = tickTraceBuffer.recent(caseId, 20);
    int activeCount = budgetEnforcer.activeCount();
    int dailyCount = budgetEnforcer.dailyCount();
    int pendingInbox = inboxManager.pendingCount(caseId, tenancyId);
    return new EvolutionStateSnapshot(
        caseId,
        Instant.now(),
        healthScore,
        componentScores,
        healthDelta,
        healthWindowMinutes,
        cbState,
        categoryStates,
        complianceLevel,
        null,
        Map.of(),
        recentTicks,
        activeCount,
        dailyCount,
        List.of(),
        config.effectiveEvolutionEnabled(),
        pendingInbox);
  }

  @Override
  public ResearchCorpusView getResearchCorpus(String query, String areaId, int limit) {
    var findings = researchCorpus.search(query, areaId, limit);
    var pending = researchCorpus.pendingHilEntries();
    return new ResearchCorpusView(findings, pending);
  }

  @Override
  public void setGatePolicy(UUID caseId, String tenancyId, GatePolicy policy) {
    gatePolicyStore.save(caseId, policy, tenancyId);
  }

  @Override
  public List<WatchPattern> getWatchPatterns(UUID caseId, String tenancyId) {
    return inboxManager.activeWatchPatterns(caseId, tenancyId);
  }

  @Override
  public GatePolicy getGatePolicy(UUID caseId, String tenancyId) {
    return gatePolicyStore.find(caseId, tenancyId);
  }

  @Override
  public List<StageDescriptor> getStages(UUID caseId) {
    return categoryRegistry.allCategories().stream()
        .map(CategoryDescriptor::domainId)
        .distinct()
        .flatMap(domain -> categoryRegistry.stagesForDomain(domain).stream())
        .toList();
  }

  @Override
  public List<CategoryDescriptor> getCategories(UUID caseId) {
    return categoryRegistry.allCategories();
  }

  @Override
  public ArtifactManifest getArtifactTrail(UUID caseId, String tenancyId, UUID improvementCaseId) {
    return artifactManifestStore.find(caseId, improvementCaseId, tenancyId);
  }

  @Override
  public List<EvolutionStateSnapshot.ImprovementStreamView> getStreamProgress(
      UUID caseId, String tenancyId) {
    var active = budgetEnforcer.activeImprovementRequests();
    if (active.isEmpty()) {
      return List.of();
    }
    List<EvolutionStateSnapshot.ImprovementStreamView> streams = new ArrayList<>();
    for (var entry : active.entrySet()) {
      var req = entry.getValue();
      var blockedBy = coordinator.blockedBy(caseId, entry.getKey(), tenancyId);
      streams.add(
          new EvolutionStateSnapshot.ImprovementStreamView(
              entry.getKey(),
              req.category(),
              req.target(),
              null,
              List.of(),
              blockedBy,
              false,
              Instant.now()));
    }
    return List.copyOf(streams);
  }
}
