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
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.BehavioralExpectations;
import io.casehub.eidos.api.BehavioralSignal;
import io.casehub.eidos.api.BehavioralSignalStore;
import io.casehub.eidos.api.ComplianceDimension;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.OptionalLong;
import org.jboss.logging.Logger;

@ApplicationScoped
public class BehavioralComplianceRecorder {

  private static final Logger LOG = Logger.getLogger(BehavioralComplianceRecorder.class);

  private final Instance<BehavioralSignalStore> signalStore;
  private final CaseDefinitionRegistry registry;

  @Inject
  public BehavioralComplianceRecorder(
      Instance<BehavioralSignalStore> signalStore, CaseDefinitionRegistry registry) {
    this.signalStore = signalStore;
    this.registry = registry;
  }

  public void record(
      CaseInstance caseInstance,
      String workerName,
      String capabilityName,
      WorkerOutcome<?> outcome,
      Long executionDurationMs) {
    if (!signalStore.isResolvable()) {
      return;
    }

    CaseDefinition definition;
    try {
      definition = registry.getCaseDefinition(caseInstance.getCaseMetaModel());
    } catch (Exception e) {
      return;
    }

    Optional<AgentDescriptor> descriptorOpt = definition.agentDescriptorFor(workerName);
    if (descriptorOpt.isEmpty()) {
      return;
    }

    AgentDescriptor descriptor = descriptorOpt.get();
    String agentId = descriptor.agentId();
    String tenancyId = caseInstance.tenancyId;

    recordLatency(agentId, tenancyId, capabilityName, descriptor, executionDurationMs);
    recordAttestation(agentId, tenancyId, capabilityName, outcome);
  }

  private void recordLatency(
      String agentId,
      String tenancyId,
      String capabilityName,
      AgentDescriptor descriptor,
      Long executionDurationMs) {
    if (executionDurationMs == null) {
      return;
    }
    AgentCapability capability =
        descriptor.capabilities().stream()
            .filter(c -> c.name().equals(capabilityName))
            .findFirst()
            .orElse(null);
    if (capability == null) {
      return;
    }
    OptionalLong bound = BehavioralExpectations.latencyBound(capability);
    if (bound.isEmpty()) {
      return;
    }
    long threshold = (long) (bound.getAsLong() * ComplianceDimension.LATENCY_VIOLATION_MULTIPLIER);
    BehavioralSignal signal =
        executionDurationMs > threshold ? BehavioralSignal.VIOLATED : BehavioralSignal.COMPLIANT;
    signalStore
        .get()
        .record(agentId, tenancyId, capabilityName, ComplianceDimension.LATENCY, signal);
    LOG.debugf(
        "Latency %s: agent=%s capability=%s duration=%dms threshold=%dms",
        signal, agentId, capabilityName, executionDurationMs, threshold);
  }

  private void recordAttestation(
      String agentId, String tenancyId, String capabilityName, WorkerOutcome<?> outcome) {
    BehavioralSignal signal =
        (outcome instanceof WorkerOutcome.Success || outcome instanceof WorkerOutcome.Completed)
            ? BehavioralSignal.COMPLIANT
            : BehavioralSignal.VIOLATED;
    signalStore
        .get()
        .record(agentId, tenancyId, capabilityName, ComplianceDimension.ATTESTATION_RATE, signal);
    LOG.debugf(
        "Attestation %s: agent=%s capability=%s outcome=%s",
        signal, agentId, capabilityName, outcome.getClass().getSimpleName());
  }
}
