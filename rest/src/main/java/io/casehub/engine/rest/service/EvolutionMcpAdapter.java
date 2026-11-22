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
package io.casehub.engine.rest.service;

import io.casehub.api.acl.AclCheck;
import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.api.model.improvement.ComplianceLevel;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ReadinessReport;
import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.spi.improvement.EngineEvolutionApi;
import io.casehub.api.view.DenyPatternView;
import io.casehub.api.view.EvolutionStateSnapshot;
import io.casehub.api.view.EvolutionSummary;
import io.casehub.neocortex.corpus.ChangeSet;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpCursorMap;
import io.casehub.platform.api.mcp.McpCursorType;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@McpDomain("engine/evolution")
public class EvolutionMcpAdapter {

  @Inject EngineEvolutionApi delegate;
  @Inject CaseService caseService;

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery("Get deny patterns that block improvement categories")
  public DenyPatternView getDenyPatterns(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getDenyPatterns(caseId, tenancyId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery(
      "Get evolution summary. Without filters returns unscoped overview. Filter by areaId, category, timeWindowMinutes, or improvementCaseId to narrow scope")
  public EvolutionSummary getSummary(
      @PathParam UUID caseId,
      @ContextParam("tenancyId") String tenancyId,
      Optional<String> areaId,
      Optional<String> category,
      Optional<Integer> timeWindowMinutes,
      Optional<UUID> improvementCaseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getSummary(
        caseId,
        tenancyId,
        areaId.orElse(null),
        category.orElse(null),
        timeWindowMinutes.orElse(null),
        improvementCaseId.orElse(null));
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery(
      "Get full evolution state — health score, circuit breaker, category states, active improvements, pending inbox count. Uses default configuration")
  public EvolutionStateSnapshot getEvolutionOverview(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getEvolutionState(caseId, tenancyId, ImprovementConfig.DEFAULTS);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery(
      "Check evolution readiness against a compliance target. targetLevel: L0_INERT, L1_OBSERVE, L2_PROPOSE, L3_AUTONOMOUS")
  public ReadinessReport checkReadiness(
      @PathParam UUID caseId,
      @ContextParam("tenancyId") String tenancyId,
      ComplianceLevel targetLevel) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getReadinessReport(caseId, tenancyId, targetLevel, ImprovementConfig.DEFAULTS);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery("Get conductor inbox — pending gate decisions that need approval or rejection")
  public List<ConductorInboxEntry> getInbox(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getInbox(caseId, tenancyId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery(
      "Get active improvement stream progress — each improvement's current stage, blocked status, and history")
  public List<EvolutionStateSnapshot.ImprovementStreamView> getStreamProgress(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getStreamProgress(caseId, tenancyId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.WRITE,
      resourceIdParam = "caseId")
  @PlatformMutation(
      "Add a deny pattern to block improvement categories. Returns updated deny patterns")
  public DenyPatternView addDenyPattern(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId, String pattern) {
    caseService.requireCaseAccess(caseId, AclAction.WRITE);
    delegate.addDenyPattern(caseId, tenancyId, pattern);
    return delegate.getDenyPatterns(caseId, tenancyId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.WRITE,
      resourceIdParam = "caseId")
  @PlatformMutation(
      "Remove a deny pattern to unblock improvement categories. Returns updated deny patterns")
  public DenyPatternView removeDenyPattern(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId, String pattern) {
    caseService.requireCaseAccess(caseId, AclAction.WRITE);
    delegate.removeDenyPattern(caseId, tenancyId, pattern);
    return delegate.getDenyPatterns(caseId, tenancyId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.WRITE,
      resourceIdParam = "caseId")
  @PlatformMutation(
      "Resolve a conductor gate decision. outcome: APPROVED, REJECTED. Returns updated inbox")
  public List<ConductorInboxEntry> resolveGate(
      @PathParam UUID caseId,
      @ContextParam("tenancyId") String tenancyId,
      String entryId,
      ConductorInboxEntry.Status outcome,
      Optional<String> reason,
      Optional<String> feedback) {
    caseService.requireCaseAccess(caseId, AclAction.WRITE);
    delegate.resolveGate(
        caseId, tenancyId, entryId, outcome, reason.orElse(null), feedback.orElse(null));
    return delegate.getInbox(caseId, tenancyId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.WRITE,
      resourceIdParam = "caseId")
  @PlatformMutation(
      "Pause a category from receiving improvements for durationMinutes. Returns updated evolution summary")
  public EvolutionSummary pauseCategory(
      @PathParam UUID caseId,
      @ContextParam("tenancyId") String tenancyId,
      String category,
      int durationMinutes) {
    caseService.requireCaseAccess(caseId, AclAction.WRITE);
    delegate.pauseCategory(caseId, tenancyId, category, durationMinutes);
    return delegate.getSummary(caseId, tenancyId, null, category, null, null);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.WRITE,
      resourceIdParam = "caseId")
  @PlatformMutation("Unpause a previously paused category. Returns updated evolution summary")
  public EvolutionSummary unpauseCategory(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId, String category) {
    caseService.requireCaseAccess(caseId, AclAction.WRITE);
    delegate.unpauseCategory(caseId, tenancyId, category);
    return delegate.getSummary(caseId, tenancyId, null, category, null, null);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.ADMIN,
      resourceIdParam = "caseId")
  @PlatformMutation(
      "Reset the health circuit breaker when it has tripped. Returns updated evolution summary")
  public EvolutionSummary resetCircuitBreaker(
      @PathParam UUID caseId, @ContextParam("tenancyId") String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    delegate.resetCircuitBreaker(caseId);
    return delegate.getSummary(caseId, tenancyId, null, null, null, null);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery(
      "Get domain-contributed stage descriptors — the lifecycle stages improvements pass through")
  public List<StageDescriptor> getStages(@PathParam UUID caseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getStages(caseId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery(
      "Get improvement category descriptors — the categories of improvements the system can propose")
  public List<CategoryDescriptor> getCategories(@PathParam UUID caseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return delegate.getCategories(caseId);
  }

  @AclCheck(
      resourceType = EngineResourceTypes.CASE,
      action = AclAction.READ,
      resourceIdParam = "caseId")
  @PlatformQuery(
      "Evolution changes since an ISO-8601 timestamp — ticks, improvements started/completed, gate decisions. Returns ChangeSet with paths (ticks/{timestamp}, improvements/{id}, gates/{id}) and a newCursor for incremental polling")
  public ChangeSet whatChanged(
      @PathParam UUID caseId,
      @ContextParam("tenancyId") String tenancyId,
      @McpCursorMap(McpCursorType.INSTANT) String since) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    var sinceInstant = java.time.Instant.parse(since);
    var ticks = delegate.getTickHistory(caseId, null);
    var streams = delegate.getStreamProgress(caseId, tenancyId);
    var inbox = delegate.getInbox(caseId, tenancyId);

    var entries = new java.util.ArrayList<io.casehub.neocortex.corpus.ChangedEntry>();
    for (var tick : ticks) {
      if (tick.timestamp().isAfter(sinceInstant)) {
        entries.add(
            new io.casehub.neocortex.corpus.ChangedEntry(
                "ticks/" + tick.timestamp(), io.casehub.neocortex.corpus.ChangeType.ADDED));
      }
    }
    for (var stream : streams) {
      if (stream.startedAt() != null && stream.startedAt().isAfter(sinceInstant)) {
        entries.add(
            new io.casehub.neocortex.corpus.ChangedEntry(
                "improvements/" + stream.improvementCaseId(),
                io.casehub.neocortex.corpus.ChangeType.ADDED));
      }
    }
    for (var entry : inbox) {
      if (entry.resolvedAt() != null && entry.resolvedAt().isAfter(sinceInstant)) {
        entries.add(
            new io.casehub.neocortex.corpus.ChangedEntry(
                "gates/" + entry.id(), io.casehub.neocortex.corpus.ChangeType.MODIFIED));
      }
    }

    String newCursor = entries.isEmpty() ? since : java.time.Instant.now().toString();
    return new ChangeSet(entries, newCursor);
  }
}
