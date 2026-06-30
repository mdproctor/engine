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
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Shared static utility for building {@link AgentCandidate} lists from a set of workers and a
 * capability.
 *
 * <p>Previously duplicated in {@code CaseContextChangedEventHandler} and {@code WorkOrchestrator}.
 * Both now delegate here so candidate construction logic (capability matching, health probing,
 * {@code agentDescriptor} passthrough) is maintained in one place.
 */
public final class AgentCandidateFactory {

  private static final Logger LOG = Logger.getLogger(AgentCandidateFactory.class);

  private AgentCandidateFactory() {}

  /**
   * Build a pre-filtered, health-probed candidate list for the given capability.
   *
   * <p>Workers without the target capability are excluded. Workers that are {@code Unavailable} are
   * excluded with a warning log. All other workers are included with their mapped {@link
   * AgentHealth} status and their {@link io.casehub.eidos.api.AgentDescriptor} (null if no
   * descriptor is registered).
   *
   * @param caseInstance the case instance — provides the UUID for health probe context
   * @param caseDefinition the case definition — provides agent descriptors for workers
   * @param workers the full list of workers from the case definition; null is treated as empty
   * @param capability the capability being routed
   * @param executionManager source of Quartz job counts for workload scoring
   * @param capabilityHealth health prober; called only when the worker has a descriptor
   * @return mutable list of eligible candidates; empty if none qualify
   */
  public static List<AgentCandidate> buildCandidates(
      final CaseInstance caseInstance,
      final CaseDefinition caseDefinition,
      final List<Worker> workers,
      final Capability capability,
      final WorkerExecutionManager executionManager,
      final CapabilityHealth capabilityHealth) {

    if (workers == null) {
      return List.of();
    }

    final List<AgentCandidate> candidates = new ArrayList<>();
    final String capabilityName = capability.name();
    final String probeContextId = caseInstance.getUuid().toString();

    for (final Worker w : workers) {
      if (w.capabilityNames() == null) {
        continue;
      }
      final boolean hasCapability = w.capabilityNames().contains(capabilityName);
      if (!hasCapability) {
        continue;
      }

      final AgentDescriptor descriptor = caseDefinition.agentDescriptorFor(w.name()).orElse(null);

      final CapabilityStatus status =
          descriptor != null
              ? capabilityHealth.probe(descriptor, capabilityName, ProbeContext.of(probeContextId))
              : new CapabilityHealth.CapabilityStatus.Ready();

      if (status instanceof CapabilityStatus.Unavailable u) {
        LOG.warnf(
            "Worker '%s' unavailable for capability '%s': %s — excluded",
            w.name(), capabilityName, u.reason());
        continue;
      }

      final AgentHealth health =
          switch (status) {
            case CapabilityStatus.EpistemicallyWeak ew -> AgentHealth.EPISTEMICALLY_WEAK;
            case CapabilityStatus.Degraded d -> AgentHealth.DEGRADED;
            default -> AgentHealth.READY;
          };

      candidates.add(
          new AgentCandidate(
              w.name(),
              w.capabilityNames(),
              executionManager.getActiveWorkCount(w.name()),
              health,
              descriptor));
    }
    return candidates;
  }
}
