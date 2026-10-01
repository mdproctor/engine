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
package io.casehub.engine.runtime.improvement;

import io.casehub.api.model.improvement.ComplianceChecklist;
import io.casehub.api.model.improvement.ComplianceChecklist.CheckRequirement;
import io.casehub.api.model.improvement.ComplianceChecklist.CheckRequirement.CheckType;
import io.casehub.api.model.improvement.ComplianceLevel;
import io.casehub.api.spi.improvement.ComplianceChecklistProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class DefaultComplianceChecklistProvider implements ComplianceChecklistProvider {

  @Override
  public ComplianceChecklist checklistFor(String areaId, ComplianceLevel level) {
    return new ComplianceChecklist(areaId, level, requirementsFor(areaId, level));
  }

  private List<CheckRequirement> requirementsFor(String areaId, ComplianceLevel level) {
    return switch (level) {
      case L0_INERT -> List.of();
      case L1_OBSERVE -> l1Requirements(areaId);
      case L2_PROPOSE -> l2Requirements(areaId);
      case L3_AUTONOMOUS -> l3Requirements(areaId);
    };
  }

  private List<CheckRequirement> l1Requirements(String areaId) {
    return List.of(
        new CheckRequirement(
            areaId + "-area-registered",
            "CapabilityArea '" + areaId + "' must be registered in CapabilityAreaRegistry",
            CheckType.INFRASTRUCTURE),
        new CheckRequirement(
            areaId + "-has-data",
            "EventLog must contain relevant events for '" + areaId + "' assessment",
            CheckType.DATA_FLOW),
        new CheckRequirement(
            areaId + "-non-neutral-score",
            "assess() must return non-neutral data (landscapePosition != ABSENT)",
            CheckType.DATA_FLOW));
  }

  private List<CheckRequirement> l2Requirements(String areaId) {
    var requirements = new ArrayList<>(l1Requirements(areaId));
    requirements.add(
        new CheckRequirement(
            "evolution-enabled",
            "ImprovementConfig.evolutionEnabled must be true",
            CheckType.CONFIGURATION));
    requirements.add(
        new CheckRequirement(
            "consensus-threshold-met",
            "ImprovementConfig.consensusMinSources must be >= 1",
            CheckType.CONFIGURATION));
    return List.copyOf(requirements);
  }

  private List<CheckRequirement> l3Requirements(String areaId) {
    var requirements = new ArrayList<>(l2Requirements(areaId));
    requirements.add(
        new CheckRequirement(
            "rollback-policy-configured",
            "ImprovementConfig.rollbackPolicy must be configured",
            CheckType.CONFIGURATION));
    requirements.add(
        new CheckRequirement(
            "health-threshold-configured",
            "HealthPolicy.healthThreshold must be explicitly set",
            CheckType.CONFIGURATION));
    return List.copyOf(requirements);
  }
}
