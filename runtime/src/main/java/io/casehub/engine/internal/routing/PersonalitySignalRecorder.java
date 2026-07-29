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
package io.casehub.engine.internal.routing;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CognitiveDemand;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Records disposition signals on worker task completion for JPAF personality adaptation. On
 * SUCCESS, reinforces the engaged cognitive function. On DECLINE/FAILURE/EXPIRED, activates the
 * compensatory function.
 */
@ApplicationScoped
public class PersonalitySignalRecorder {

  private static final Logger LOG = Logger.getLogger(PersonalitySignalRecorder.class);

  private final DispositionSignalStore signalStore;
  private final CaseDefinitionRegistry registry;
  private final DispositionHealth dispositionHealth;
  private final DispositionEvolution dispositionEvolution;

  @Inject
  public PersonalitySignalRecorder(
      DispositionSignalStore signalStore,
      CaseDefinitionRegistry registry,
      DispositionHealth dispositionHealth,
      DispositionEvolution dispositionEvolution) {
    this.signalStore = signalStore;
    this.registry = registry;
    this.dispositionHealth = dispositionHealth;
    this.dispositionEvolution = dispositionEvolution;
  }

  public void record(
      CaseInstance caseInstance,
      String workerName,
      String capabilityName,
      WorkerOutcome<?> outcome) {
    CaseDefinition definition;
    try {
      definition = registry.getCaseDefinition(caseInstance.getCaseMetaModel());
    } catch (Exception e) {
      return;
    }

    Optional<AgentDescriptor> descriptorOpt = definition.agentDescriptorFor(workerName);
    if (descriptorOpt.isEmpty()) {return;}

    AgentDescriptor descriptor = descriptorOpt.get();
    if (descriptor.disposition() == null
        || descriptor.disposition().dispositionProfile().isEmpty()) {
      return;
    }

    CognitiveDemand demand = definition.getCognitiveDemand(capabilityName);
    if (demand == null) {return;}

    List<DispositionValue> profile   = descriptor.disposition().dispositionProfile();
    String                 agentId   = descriptor.agentId();
    String                 tenancyId = caseInstance.tenancyId;

    if (outcome instanceof WorkerOutcome.Success) {
      recordReinforcement(agentId, tenancyId, profile, demand);
    } else {
      recordCompensation(agentId, tenancyId, profile, demand);
    }

    checkReflection(agentId, tenancyId, descriptor);}

  void recordReinforcement(
      String agentId,
      String tenancyId,
      List<DispositionValue> profile,
      CognitiveDemand demand) {
    String dominant = profile.get(0).term();
    String auxiliary = profile.size() > 1 ? profile.get(1).term() : null;

    double domDemand = demand.functionWeights().getOrDefault(dominant, 0.0);
    double auxDemand =
        auxiliary != null ? demand.functionWeights().getOrDefault(auxiliary, 0.0) : 0.0;

    String engaged = domDemand >= auxDemand ? dominant : auxiliary;
    if (engaged == null) engaged = dominant;

    signalStore.recordActivation(agentId, tenancyId, engaged);
    LOG.debugf("Personality reinforcement: agent=%s function=%s", agentId, engaged);
  }

  void recordCompensation(
      String agentId,
      String tenancyId,
      List<DispositionValue> profile,
      CognitiveDemand demand) {
    Set<String> domAux = new HashSet<>();
    domAux.add(profile.get(0).term());
    if (profile.size() > 1) domAux.add(profile.get(1).term());

    String compensatory =
        demand.functionWeights().entrySet().stream()
            .filter(e -> !domAux.contains(e.getKey()))
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

    if (compensatory == null) {
      LOG.debugf("No compensatory function for agent=%s — all demand on dom/aux", agentId);
      return;
    }

    signalStore.recordActivation(agentId, tenancyId, compensatory);
    LOG.debugf("Personality compensation: agent=%s function=%s", agentId, compensatory);
  }

  void checkReflection(String agentId, String tenancyId, AgentDescriptor descriptor) {
    try {
      var status =
              dispositionHealth.probe(
                      descriptor,
                      new CapabilityHealth.ProbeContext(null, java.util.Map.of()));

      if (status instanceof DispositionHealth.DispositionStatus.EvolutionPending pending) {
        var result = dispositionEvolution.evaluate(descriptor, pending);
        switch (result) {
          case DispositionEvolution.EvolutionResult.Evolved evolved -> LOG.infof(
                  "Personality evolved: agent=%s %s->%s",
                  agentId, evolved.previousTypeLabel(), evolved.newTypeLabel());
          case DispositionEvolution.EvolutionResult.Dampened dampened -> {
            signalStore.decay(agentId, tenancyId, dampened.decayFactor());
            LOG.infof(
                    "Personality dampened: agent=%s factor=%.2f", agentId, dampened.decayFactor());
          }
        }
      }
    } catch (Exception e) {
      LOG.warnf(e, "Reflection check failed for agent=%s — continuing without reflection", agentId);
    }}
}
