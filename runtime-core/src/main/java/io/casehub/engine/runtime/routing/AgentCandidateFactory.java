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
import io.casehub.api.spi.routing.CandidateMatchingContext;
import io.casehub.api.spi.routing.CandidateMatchingStrategy;
import io.casehub.api.spi.routing.MatchedWorker;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

public class AgentCandidateFactory {

  private static final Logger LOG = Logger.getLogger(AgentCandidateFactory.class);

  private final StrategyResolver strategyResolver;

  public AgentCandidateFactory(final StrategyResolver strategyResolver) {
    this.strategyResolver = strategyResolver;
  }

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

    final String capabilityName = capability.name();
    final String probeContextId = caseInstance.getUuid().toString();

    final CandidateMatchingStrategy matchingStrategy =
        strategyResolver.resolve(
            CandidateMatchingStrategy.class, caseDefinition.getCandidateMatching());

    final List<MatchedWorker> matched =
        matchingStrategy.match(
            new CandidateMatchingContext(capabilityName, workers, caseDefinition));

    final List<AgentCandidate> candidates = new ArrayList<>();
    for (final MatchedWorker mw : matched) {
      final Worker w = mw.worker();
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
      if (status instanceof CapabilityStatus.Excluded ex) {
        LOG.warnf(
            "Worker '%s' excluded for capability '%s': %s — excluded",
            w.name(), capabilityName, ex.domain());
        continue;
      }

      final AgentHealth health =
          switch (status) {
            case CapabilityStatus.Ready r -> AgentHealth.READY;
            case CapabilityStatus.BehavioralViolation bv -> AgentHealth.BEHAVIORAL_VIOLATION;
            case CapabilityStatus.EpistemicallyWeak ew -> AgentHealth.EPISTEMICALLY_WEAK;
            case CapabilityStatus.Degraded d -> AgentHealth.DEGRADED;
            case CapabilityStatus.Overloaded o -> AgentHealth.DEGRADED;
            case CapabilityStatus.Unavailable u ->
                throw new IllegalStateException("unreachable — filtered above");
            case CapabilityStatus.Excluded ex ->
                throw new IllegalStateException("unreachable — filtered above");
          };

      final java.util.Map<String, Integer> violations =
          status instanceof CapabilityStatus.BehavioralViolation bv ? bv.violations() : null;

      candidates.add(
          new AgentCandidate(
              w.name(),
              w.capabilities(),
              executionManager.getActiveWorkCount(w.name()),
              health,
              descriptor,
              mw.matchDegree(),
              violations));
    }
    return candidates;
  }
}
