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
package io.casehub.api.model.improvement;

import io.casehub.api.model.stigmergy.EscalationPolicy;
import io.casehub.api.model.stigmergy.GatePolicy;
import io.casehub.api.model.stigmergy.ResearchMethodology;
import jakarta.annotation.Nullable;
import java.util.List;

public record ImprovementConfig(
    @Nullable String signalNamespace,
    @Nullable Integer consensusMinSources,
    @Nullable List<String> enabledCategories,
    @Nullable ImprovementBudget budget,
    @Nullable String caseTemplateId,
    @Nullable Boolean evolutionEnabled,
    @Nullable Integer evolutionTickIntervalMinutes,
    @Nullable RollbackPolicy rollbackPolicy,
    @Nullable HealthPolicy healthPolicy,
    @Nullable ResearchMethodology researchMethodology,
    @Nullable Integer conflictTrivialThreshold,
    @Nullable GatePolicy gatePolicy,
    @Nullable EscalationPolicy escalationPolicy) {

  public ImprovementConfig(
      @Nullable String signalNamespace,
      @Nullable Integer consensusMinSources,
      @Nullable List<String> enabledCategories,
      @Nullable ImprovementBudget budget,
      @Nullable String caseTemplateId,
      @Nullable Boolean evolutionEnabled,
      @Nullable Integer evolutionTickIntervalMinutes,
      @Nullable RollbackPolicy rollbackPolicy,
      @Nullable HealthPolicy healthPolicy,
      @Nullable ResearchMethodology researchMethodology,
      @Nullable Integer conflictTrivialThreshold) {
    this(
        signalNamespace,
        consensusMinSources,
        enabledCategories,
        budget,
        caseTemplateId,
        evolutionEnabled,
        evolutionTickIntervalMinutes,
        rollbackPolicy,
        healthPolicy,
        researchMethodology,
        conflictTrivialThreshold,
        null,
        null);
  }

  public ImprovementConfig(
      @Nullable String signalNamespace,
      @Nullable Integer consensusMinSources,
      @Nullable List<String> enabledCategories,
      @Nullable ImprovementBudget budget,
      @Nullable String caseTemplateId) {
    this(
        signalNamespace,
        consensusMinSources,
        enabledCategories,
        budget,
        caseTemplateId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public String effectiveSignalNamespace() {
    return signalNamespace != null ? signalNamespace : "improvement";
  }

  public int effectiveConsensusMinSources() {
    return consensusMinSources != null ? consensusMinSources : 2;
  }

  public ImprovementBudget effectiveBudget() {
    return budget != null
        ? budget
        : new ImprovementBudget(null, null, null, null, null, null, null);
  }

  public String effectiveCaseTemplateId() {
    return caseTemplateId != null ? caseTemplateId : "self-improvement";
  }

  public boolean effectiveEvolutionEnabled() {
    return evolutionEnabled != null ? evolutionEnabled : false;
  }

  public int effectiveEvolutionTickIntervalMinutes() {
    return evolutionTickIntervalMinutes != null ? evolutionTickIntervalMinutes : 60;
  }

  public RollbackPolicy effectiveRollbackPolicy() {
    return rollbackPolicy != null
        ? rollbackPolicy
        : new RollbackPolicy(null, null, null, null, null, null);
  }

  public HealthPolicy effectiveHealthPolicy() {
    return healthPolicy != null
        ? healthPolicy
        : new HealthPolicy(null, null, null, null, null, null);
  }

  public ResearchMethodology effectiveResearchMethodology() {
    return researchMethodology != null
        ? researchMethodology
        : new ResearchMethodology(null, null, null, null, null, null);
  }

  public int effectiveConflictTrivialThreshold() {
    return conflictTrivialThreshold != null ? conflictTrivialThreshold : 10;
  }

  public GatePolicy effectiveGatePolicy() {
    return gatePolicy != null ? gatePolicy : new GatePolicy(null, null);
  }

  public EscalationPolicy effectiveEscalationPolicy() {
    return escalationPolicy != null ? escalationPolicy : new EscalationPolicy(null, null);
  }
}
