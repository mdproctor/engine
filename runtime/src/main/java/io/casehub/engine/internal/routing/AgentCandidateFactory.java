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
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.eidos.api.CapabilityResolver;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Builds {@link AgentCandidate} lists from a set of workers and a capability.
 *
 * <p>Two-tier matching: exact string match on {@link Worker#capabilityNames()} first (fast path),
 * then vocabulary-grounded subsumption via {@link CapabilityResolver} when the worker has an {@link
 * AgentDescriptor} with grounded capabilities.
 *
 * <p>Refs casehubio/engine#609.
 */
@ApplicationScoped
public class AgentCandidateFactory {

  private static final Logger LOG = Logger.getLogger(AgentCandidateFactory.class);

  private final VocabularyRegistry vocabularyRegistry;

  @Inject
  public AgentCandidateFactory(final VocabularyRegistry vocabularyRegistry) {
    this.vocabularyRegistry = vocabularyRegistry;
  }

  /**
   * Build a pre-filtered, health-probed candidate list for the given capability.
   *
   * <p>Workers are matched in two tiers:
   *
   * <ol>
   *   <li><b>Exact match:</b> {@code worker.capabilityNames().contains(capabilityName)}
   *   <li><b>Subsumption:</b> when no exact match and the worker has an {@link AgentDescriptor},
   *       uses {@link CapabilityResolver#resolve} to find a vocabulary-grounded match
   * </ol>
   *
   * <p>Workers that are {@code Unavailable} are excluded with a warning log. All other workers are
   * included with their mapped {@link AgentHealth} status.
   */
  public List<AgentCandidate> buildCandidates(
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

      final AgentDescriptor descriptor = caseDefinition.agentDescriptorFor(w.name()).orElse(null);
      final boolean exactMatch = w.capabilityNames().contains(capabilityName);

      if (!exactMatch) {
        if (descriptor == null || descriptor.capabilities().isEmpty()) {
          continue;
        }
        final AgentCapability resolved =
            CapabilityResolver.resolve(
                descriptor.capabilities(), capabilityName, vocabularyRegistry);
        if (resolved == null) {
          continue;
        }
        LOG.debugf(
            "Worker '%s' matched capability '%s' via subsumption (descriptor capability '%s')",
            w.name(), capabilityName, resolved.name());
      }

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
