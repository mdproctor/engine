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

import io.casehub.api.model.improvement.ComplianceChecklist.CheckRequirement;
import io.casehub.api.model.improvement.ComplianceLevel;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ReadinessReport;
import io.casehub.api.model.improvement.ReadinessReport.AreaCompliance;
import io.casehub.api.model.improvement.ReadinessReport.CheckResult;
import io.casehub.api.spi.improvement.CapabilityArea;
import io.casehub.api.spi.improvement.ComplianceChecklistProvider;
import io.casehub.engine.common.spi.event.ComplianceLevelChangedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ReadinessValidator {

  private final CapabilityAreaRegistry areaRegistry;
  private final ComplianceChecklistProvider checklistProvider;
  private final Event<ComplianceLevelChangedEvent> complianceLevelChangedEvent;
  private final ConcurrentHashMap<UUID, ComplianceLevel> cachedLevels = new ConcurrentHashMap<>();

  @Inject
  public ReadinessValidator(
      CapabilityAreaRegistry areaRegistry,
      ComplianceChecklistProvider checklistProvider,
      Event<ComplianceLevelChangedEvent> complianceLevelChangedEvent) {
    this.areaRegistry = areaRegistry;
    this.checklistProvider = checklistProvider;
    this.complianceLevelChangedEvent = complianceLevelChangedEvent;
  }

  public ReadinessReport validate(
      UUID caseId, String tenancyId, ComplianceLevel targetLevel, ImprovementConfig config) {
    var areas = new ArrayList<AreaCompliance>();
    for (CapabilityArea area : areaRegistry.active()) {
      var areaLevel = computeAreaLevel(area, caseId, tenancyId, config);
      var checks = evaluateChecks(area, caseId, tenancyId, targetLevel, config);
      areas.add(new AreaCompliance(area.id(), areaLevel, checks));
    }
    var projectLevel = computeProjectLevel(areas);
    boolean passed = projectLevel.compareTo(targetLevel) >= 0;

    var previousLevel = cachedLevels.put(caseId, projectLevel);
    if (previousLevel != null && previousLevel != projectLevel) {
      complianceLevelChangedEvent.fireAsync(
          new ComplianceLevelChangedEvent(caseId, previousLevel, projectLevel));
    }

    return new ReadinessReport(targetLevel, projectLevel, areas, passed, Instant.now());
  }

  public ComplianceLevel cachedLevel(UUID caseId) {
    return cachedLevels.getOrDefault(caseId, ComplianceLevel.L0_INERT);
  }

  private ComplianceLevel computeAreaLevel(
      CapabilityArea area, UUID caseId, String tenancyId, ImprovementConfig config) {
    for (var level :
        List.of(
            ComplianceLevel.L3_AUTONOMOUS,
            ComplianceLevel.L2_PROPOSE,
            ComplianceLevel.L1_OBSERVE)) {
      var checks = evaluateChecks(area, caseId, tenancyId, level, config);
      if (checks.stream().allMatch(CheckResult::satisfied)) {
        return level;
      }
    }
    return ComplianceLevel.L0_INERT;
  }

  private ComplianceLevel computeProjectLevel(List<AreaCompliance> areas) {
    return areas.stream()
        .map(AreaCompliance::areaLevel)
        .filter(l -> l != ComplianceLevel.L0_INERT)
        .min(Comparator.naturalOrder())
        .orElse(ComplianceLevel.L0_INERT);
  }

  private List<CheckResult> evaluateChecks(
      CapabilityArea area,
      UUID caseId,
      String tenancyId,
      ComplianceLevel level,
      ImprovementConfig config) {
    var checklist = checklistProvider.checklistFor(area.id(), level);
    var results = new ArrayList<CheckResult>();
    for (var req : checklist.requirements()) {
      results.add(evaluateRequirement(req, area, caseId, tenancyId, config));
    }
    return List.copyOf(results);
  }

  private CheckResult evaluateRequirement(
      CheckRequirement req,
      CapabilityArea area,
      UUID caseId,
      String tenancyId,
      ImprovementConfig config) {
    return switch (req.type()) {
      case INFRASTRUCTURE -> evaluateInfrastructure(req, area);
      case DATA_FLOW -> evaluateDataFlow(req, area, caseId, tenancyId);
      case CONFIGURATION -> evaluateConfiguration(req, config);
    };
  }

  private CheckResult evaluateInfrastructure(CheckRequirement req, CapabilityArea area) {
    boolean registered = areaRegistry.get(area.id()).isPresent();
    return new CheckResult(
        req.name(), registered, "registered", registered ? "registered" : "not registered", null);
  }

  private CheckResult evaluateDataFlow(
      CheckRequirement req, CapabilityArea area, UUID caseId, String tenancyId) {
    var assessment = area.assess(caseId, tenancyId);
    boolean hasData =
        assessment.landscapePosition()
            != io.casehub.api.model.stigmergy.CapabilityAreaAssessment.LandscapePosition.ABSENT;
    return new CheckResult(
        req.name(),
        hasData,
        "non-neutral data",
        hasData ? "data present" : "ABSENT (no data)",
        hasData ? null : "Ensure EventLog has relevant events for this area");
  }

  private CheckResult evaluateConfiguration(CheckRequirement req, ImprovementConfig config) {
    return switch (req.name()) {
      case "evolution-enabled" ->
          new CheckResult(
              req.name(),
              config.effectiveEvolutionEnabled(),
              "true",
              String.valueOf(config.effectiveEvolutionEnabled()),
              config.effectiveEvolutionEnabled()
                  ? null
                  : "Set ImprovementConfig.evolutionEnabled = true");
      case "consensus-threshold-met" -> {
        boolean met = config.effectiveConsensusMinSources() >= 1;
        yield new CheckResult(
            req.name(),
            met,
            ">= 1",
            String.valueOf(config.effectiveConsensusMinSources()),
            met ? null : "Set ImprovementConfig.consensusMinSources >= 1");
      }
      case "rollback-policy-configured" -> {
        boolean configured = config.rollbackPolicy() != null;
        yield new CheckResult(
            req.name(),
            configured,
            "configured",
            configured ? "configured" : "not configured",
            configured ? null : "Set ImprovementConfig.rollbackPolicy");
      }
      case "health-threshold-configured" -> {
        boolean configured =
            config.healthPolicy() != null && config.healthPolicy().healthThreshold() != null;
        yield new CheckResult(
            req.name(),
            configured,
            "configured",
            configured ? "configured" : "not configured",
            configured ? null : "Set HealthPolicy.healthThreshold explicitly");
      }
      default -> new CheckResult(req.name(), false, "unknown", "unknown", null);
    };
  }
}
