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
package io.casehub.engine.internal.stigmergy;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.*;
import io.casehub.api.spi.*;
import io.casehub.api.spi.stigmergy.SwarmProvisioningAdvisor;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class SwarmProvisioner implements Resettable {

  private final WorkerProvisioner workerProvisioner;
  private final StigmergyCoordinator coordinator;
  private final SignalRegistry signalRegistry;
  private final ActivityTracker activityTracker;
  private final RoleTracker roleTracker;
  private final TeamDetector teamDetector;
  private final SwarmProgressTracker progressTracker;
  private final DispatchBudget dispatchBudget;
  private Instance<SwarmProvisioningAdvisor> advisorInstance;

  private final ConcurrentHashMap<UUID, CaseProvisionState> cases = new ConcurrentHashMap<>();

  @Inject
  public SwarmProvisioner(
      WorkerProvisioner workerProvisioner,
      StigmergyCoordinator coordinator,
      SignalRegistry signalRegistry,
      ActivityTracker activityTracker,
      RoleTracker roleTracker,
      TeamDetector teamDetector,
      SwarmProgressTracker progressTracker,
      DispatchBudget dispatchBudget) {
    this.workerProvisioner = workerProvisioner;
    this.coordinator = coordinator;
    this.signalRegistry = signalRegistry;
    this.activityTracker = activityTracker;
    this.roleTracker = roleTracker;
    this.teamDetector = teamDetector;
    this.progressTracker = progressTracker;
    this.dispatchBudget = dispatchBudget;
  }

  public List<SwarmEvent> evaluateAndProvision(
      UUID caseId, SwarmConfig swarmConfig, StigmergyConfig stigConfig) {
    var state = cases.computeIfAbsent(caseId, k -> new CaseProvisionState());

    if (!state.provisionLock.tryLock()) {
      return List.of();
    }
    try {
      return doProvision(caseId, swarmConfig, stigConfig, state);
    } finally {
      state.provisionLock.unlock();
    }
  }

  private List<SwarmEvent> doProvision(
      UUID caseId, SwarmConfig swarmConfig, StigmergyConfig stigConfig, CaseProvisionState state) {
    List<SwarmEvent> events = new ArrayList<>();

    int currentActive = coordinator.activeAgents(caseId).size();
    if (currentActive >= swarmConfig.effectiveMaxSwarmSize()) {
      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED,
              Map.of(
                  "reason",
                  "maxSwarmSize",
                  "current",
                  currentActive,
                  "limit",
                  swarmConfig.effectiveMaxSwarmSize())));
      return events;
    }

    var budget =
        swarmConfig.provisionBudget() != null
            ? swarmConfig.provisionBudget()
            : new ProvisionBudget(null, null, null, null, null);

    if (state.totalProvisions.get() >= budget.effectiveMaxProvisions()) {
      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED,
              Map.of(
                  "reason", "maxProvisions",
                  "current", state.totalProvisions.get(),
                  "limit", budget.effectiveMaxProvisions())));
      return events;
    }

    long swarmProvisioned =
        state.provisionedAgents.values().stream()
            .filter(m -> isAgentActive(caseId, m.agentId()))
            .count();
    if (swarmProvisioned >= budget.effectiveMaxConcurrent()) {
      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED,
              Map.of(
                  "reason",
                  "maxConcurrent",
                  "current",
                  swarmProvisioned,
                  "limit",
                  budget.effectiveMaxConcurrent())));
      return events;
    }

    if (state.cycleCount.get() - state.lastProvisionCycle.get()
        < budget.effectiveCooldownCycles()) {
      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED,
              Map.of(
                  "reason",
                  "cooldown",
                  "cyclesSinceLastProvision",
                  state.cycleCount.get() - state.lastProvisionCycle.get(),
                  "cooldownRequired",
                  budget.effectiveCooldownCycles())));
      return events;
    }

    if (dispatchBudget.availableCapacity(new DispatchBudgetQuery(caseId, null)) <= 0) {
      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED,
              Map.of("reason", "dispatchBudget")));
      return events;
    }

    events.add(
        new SwarmEvent(
            CaseHubEventType.SWARM_PROVISION_REQUESTED,
            Map.of("caseId", caseId, "activeAgents", currentActive)));

    if (advisorInstance != null && advisorInstance.isResolvable()) {
      var advisor = advisorInstance.get();
      var bootstrapCtx = buildBootstrapContext(caseId, swarmConfig, null);
      var adviceCtx =
          new SwarmProvisioningAdvisor.ProvisioningContext(
              caseId, bootstrapCtx, Set.of(), swarmConfig.integrationPolicy());
      var advice = advisor.advise(adviceCtx);
      if (!advice.shouldProvision()) {
        events.add(
            new SwarmEvent(
                CaseHubEventType.SWARM_PROVISION_VETOED,
                Map.of("reasoning", advice.reasoning() != null ? advice.reasoning() : "")));
        return events;
      }
    }

    String bindingName =
        "swarm-"
            + caseId.toString().substring(0, 8)
            + "-"
            + state.provisionCounter.incrementAndGet();

    var provisionContext =
        new ProvisionContext(caseId, null, "swarm-agent", null, null, null, null, null);

    try {
      var result =
          workerProvisioner.provision(workerProvisioner.getCapabilities(), provisionContext);

      String agentId = result.resolvedWorkerId() != null ? result.resolvedWorkerId() : bindingName;
      coordinator.agentJoined(caseId, agentId, bindingName);
      coordinator.agentActivated(caseId, agentId);

      var policy =
          swarmConfig.integrationPolicy() != null
              ? swarmConfig.integrationPolicy()
              : IntegrationPolicy.BALANCED;

      state.provisionedAgents.put(
          agentId,
          new ProvisionedAgentMeta(
              agentId,
              bindingName,
              state.cycleCount.get(),
              policy.effectiveIntegrationDelay(),
              Set.of(),
              Instant.now(),
              0));
      state.totalProvisions.incrementAndGet();
      state.lastProvisionCycle.set(state.cycleCount.get());

      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROVISION_COMPLETED,
              Map.of(
                  "agentId", agentId,
                  "bindingName", bindingName,
                  "integrationDelay", policy.effectiveIntegrationDelay())));
    } catch (ProvisioningException e) {
      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROVISION_FAILED,
              Map.of("reason", e.getMessage() != null ? e.getMessage() : "unknown")));
    }
    return events;
  }

  public List<SwarmEvent> evaluateDeprovisioning(UUID caseId, SwarmConfig config) {
    var state = cases.get(caseId);
    if (state == null) return List.of();

    var budget =
        config.provisionBudget() != null
            ? config.provisionBudget()
            : new ProvisionBudget(null, null, null, null, null);

    List<SwarmEvent> events = new ArrayList<>();
    List<String> toRemove = new ArrayList<>();

    for (var entry : state.provisionedAgents.entrySet()) {
      var meta = entry.getValue();

      if (meta.integrationDelayRemaining() > 0) continue;

      if (!isAgentActive(caseId, meta.agentId())) {
        toRemove.add(meta.agentId());
        continue;
      }

      var activityState = activityTracker.getState(caseId);
      double rate = activityState.dispatchRate(Duration.ofSeconds(30), Instant.now());

      if (rate < budget.effectiveIdleThreshold()) {
        int newIdleCycles = meta.consecutiveIdleCycles() + 1;
        state.provisionedAgents.put(
            meta.agentId(),
            new ProvisionedAgentMeta(
                meta.agentId(),
                meta.bindingName(),
                meta.provisionedAtCycle(),
                meta.integrationDelayRemaining(),
                meta.requestedCapabilities(),
                meta.provisionedAt(),
                newIdleCycles));

        if (newIdleCycles >= budget.effectiveIdleGraceCycles()) {
          if (!isProvisioningSignalReinforced(caseId)) {
            coordinator.agentDeparted(caseId, meta.agentId());
            workerProvisioner.terminate(meta.agentId(), null);
            toRemove.add(meta.agentId());
            events.add(
                new SwarmEvent(
                    CaseHubEventType.SWARM_AGENT_TERMINATED,
                    Map.of(
                        "agentId", meta.agentId(), "reason", "idle", "idleCycles", newIdleCycles)));
          }
        }
      } else {
        state.provisionedAgents.put(
            meta.agentId(),
            new ProvisionedAgentMeta(
                meta.agentId(),
                meta.bindingName(),
                meta.provisionedAtCycle(),
                meta.integrationDelayRemaining(),
                meta.requestedCapabilities(),
                meta.provisionedAt(),
                0));
      }
    }

    for (String id : toRemove) {
      state.provisionedAgents.remove(id);
    }
    return events;
  }

  private boolean isProvisioningSignalReinforced(UUID caseId) {
    var allSignals = signalRegistry.getAllSignals(caseId);
    return allSignals.keySet().stream()
        .filter(name -> name.startsWith("swarm:need-capacity"))
        .anyMatch(name -> !allSignals.get(name).expired());
  }

  public boolean isInIntegrationDelay(UUID caseId, String agentId) {
    var state = cases.get(caseId);
    if (state == null) return false;
    var meta = state.provisionedAgents.get(agentId);
    return meta != null && meta.integrationDelayRemaining() > 0;
  }

  public void decrementIntegrationDelay(UUID caseId, String agentId) {
    var state = cases.get(caseId);
    if (state == null) return;
    var meta = state.provisionedAgents.get(agentId);
    if (meta != null && meta.integrationDelayRemaining() > 0) {
      state.provisionedAgents.put(
          agentId,
          new ProvisionedAgentMeta(
              meta.agentId(),
              meta.bindingName(),
              meta.provisionedAtCycle(),
              meta.integrationDelayRemaining() - 1,
              meta.requestedCapabilities(),
              meta.provisionedAt(),
              meta.consecutiveIdleCycles()));
    }
  }

  public void incrementCycleCount(UUID caseId) {
    cases.computeIfAbsent(caseId, k -> new CaseProvisionState()).cycleCount.incrementAndGet();
  }

  SwarmBootstrapContext buildBootstrapContext(
      UUID caseId, SwarmConfig config, ProvisioningRequest request) {
    var policy =
        config.integrationPolicy() != null
            ? config.integrationPolicy()
            : IntegrationPolicy.BALANCED;
    double richness = policy.effectiveBootstrapRichness();

    Map<String, Double> activeSignals = Map.of();
    Set<String> activeInterestKeys = Set.of();
    List<DetectedRole> currentRoles = List.of();
    List<DetectedTeam> currentTeams = List.of();
    SwarmProgress swarmProgress = SwarmProgress.EMPTY;

    if (richness > 0.0) {
      swarmProgress = progressTracker.getProgress(caseId);
    }
    if (richness > 0.3) {
      var allSignals = signalRegistry.getAllSignals(caseId);
      Map<String, Double> sigMap = new HashMap<>();
      for (var entry : allSignals.entrySet()) {
        if (!entry.getValue().expired()) {
          sigMap.put(entry.getKey(), entry.getValue().strength());
        }
      }
      activeSignals = sigMap;
    }
    if (richness > 0.6) {
      currentRoles = roleTracker.getDetectedRoles(caseId);
      currentTeams = teamDetector.getDetectedTeams(caseId);
    }

    return new SwarmBootstrapContext(
        activeSignals,
        activeInterestKeys,
        currentRoles,
        currentTeams,
        swarmProgress,
        request,
        policy);
  }

  private boolean isAgentActive(UUID caseId, String agentId) {
    return coordinator.activeAgents(caseId).stream().anyMatch(a -> a.agentId().equals(agentId));
  }

  public void evictByCase(UUID caseId) {
    cases.remove(caseId);
  }

  @Override
  public void reset() {
    cases.clear();
  }

  record ProvisionedAgentMeta(
      String agentId,
      String bindingName,
      int provisionedAtCycle,
      int integrationDelayRemaining,
      Set<String> requestedCapabilities,
      Instant provisionedAt,
      int consecutiveIdleCycles) {

    ProvisionedAgentMeta {
      requestedCapabilities = Set.copyOf(requestedCapabilities);
    }
  }

  private static class CaseProvisionState {
    final ReentrantLock provisionLock = new ReentrantLock();
    final AtomicInteger totalProvisions = new AtomicInteger();
    final AtomicInteger lastProvisionCycle = new AtomicInteger(-1000);
    final AtomicInteger cycleCount = new AtomicInteger();
    final AtomicInteger provisionCounter = new AtomicInteger();
    final ConcurrentHashMap<String, ProvisionedAgentMeta> provisionedAgents =
        new ConcurrentHashMap<>();
  }
}
