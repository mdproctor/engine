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
package io.casehub.blackboard.control;

import io.casehub.api.engine.LoopControl;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Worker;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * {@link LoopControl} implementation that delegates selection to a {@link PlanningStrategy},
 * managing a {@link CasePlanModel} per case.
 *
 * <p>Activated via {@code @Alternative @Priority(10)} — replaces {@link
 * io.casehub.engine.internal.engine.ChoreographyLoopControl} when {@code casehub-blackboard} is on
 * the classpath. See casehubio/engine#76. Epic: casehubio/engine#30.
 */
@Alternative
@Priority(10)
@ApplicationScoped
public class PlanningStrategyLoopControl implements LoopControl {

  private static final Logger LOG = Logger.getLogger(PlanningStrategyLoopControl.class);

  private final BlackboardRegistry registry;
  private final PlanningStrategy planningStrategy;
  private final StageLifecycleEvaluator stageLifecycleEvaluator;
  private final Instance<BlackboardPlanConfigurer> configurers;

  @Inject
  public PlanningStrategyLoopControl(
      BlackboardRegistry registry,
      PlanningStrategy planningStrategy,
      StageLifecycleEvaluator stageLifecycleEvaluator,
      Instance<BlackboardPlanConfigurer> configurers) {
    this.registry = registry;
    this.planningStrategy = planningStrategy;
    this.stageLifecycleEvaluator = stageLifecycleEvaluator;
    this.configurers = configurers;
  }

  @Override
  public Uni<List<Binding>> select(PlanExecutionContext ctx, List<Binding> eligible) {
    UUID caseId = ctx.caseId();
    CasePlanModel plan = registry.getOrCreate(caseId);

    // On the first select() call for a case, run all applicable BlackboardPlanConfigurer beans.
    // markConfigured() is atomic — only returns true once, guaranteeing exactly-once invocation.
    if (registry.markConfigured(caseId)) {
      configurers.stream()
          .filter(c -> c.supports(ctx.definition()))
          .forEach(c -> c.configure(plan, ctx));
    }

    // Stage-gating (ADR-0002): compute which binding names are "owned" by at least one stage
    // (allStagedNames), and which of those stages are currently ACTIVE (activeStagedNames).
    // Free-floating bindings (not in allStagedNames) always pass.
    // Staged bindings only pass when their stage is ACTIVE.
    // If no stage declares any binding, allStagedNames is empty → gatedEligible == eligible
    // (pure choreography, no behaviour change).
    Set<String> allStagedNames =
        plan.getAllStages().stream()
            .flatMap(s -> s.getContainedBindingNames().stream())
            .collect(Collectors.toSet());

    Set<String> activeStagedNames =
        plan.getActiveStages().stream()
            .flatMap(s -> s.getContainedBindingNames().stream())
            .collect(Collectors.toSet());

    List<Binding> gatedEligible =
        allStagedNames.isEmpty()
            ? eligible // fast path: pure choreography — avoid stream allocation
            : eligible.stream()
                .filter(
                    b ->
                        !allStagedNames.contains(b.getName()) // free-floating → always pass
                            || activeStagedNames.contains(b.getName())) // staged → only if ACTIVE
                .collect(Collectors.toList());

    // Create a PlanItem for each gated-eligible Binding and add to agenda, skipping duplicates.
    // addPlanItemIfAbsent performs the check-and-insert atomically — no TOCTOU window.
    gatedEligible.forEach(
        binding -> {
          String workerName = resolveWorkerName(binding, ctx);
          plan.addPlanItemIfAbsent(
              PlanItem.create(binding.getName(), workerName, 0, binding.target()));
        });

    return stageLifecycleEvaluator
        .evaluate(plan, ctx)
        .chain(() -> planningStrategy.select(plan, ctx, gatedEligible))
        .invoke(selected -> indexSelectedForCompletion(caseId, selected, plan));
  }

  /**
   * Resolves the worker name for a Binding by matching its capability against the case definition's
   * worker list. Returns the capability name as fallback if no worker matches.
   */
  private String resolveWorkerName(Binding binding, PlanExecutionContext ctx) {
    if (!(binding.target() instanceof io.casehub.api.model.CapabilityTarget ct)) return "unknown";
    String capName = ct.capability().getName();
    List<Worker> matching =
        ctx.definition().getWorkers().stream()
            .filter(
                w ->
                    w.getCapabilities() != null
                        && w.getCapabilities().stream().anyMatch(c -> c.getName().equals(capName)))
            .toList();
    if (matching.size() > 1) {
      LOG.warnf(
          "Capability '%s' matched %d workers — only '%s' will be tracked for PlanItem completion. "
              + "Workers [%s] will fire but their completion events will be silently ignored, "
              + "leaving their PlanItems RUNNING indefinitely. "
              + "Multi-worker fan-out requires per-worker PlanItems (casehubio/engine#82).",
          capName,
          matching.size(),
          matching.get(0).getName(),
          matching.stream()
              .skip(1)
              .map(Worker::getName)
              .collect(java.util.stream.Collectors.joining(", ")));
    }
    return matching.isEmpty() ? capName : matching.get(0).getName();
  }

  /**
   * For each selected Binding, marks its PlanItem RUNNING and registers the worker-name →
   * planItemId mapping for completion tracking.
   */
  private void indexSelectedForCompletion(UUID caseId, List<Binding> selected, CasePlanModel plan) {
    for (Binding binding : selected) {
      plan.getAgenda().stream()
          .filter(
              pi ->
                  pi.getBindingName().equals(binding.getName())
                      && pi.getStatus() == PlanItem.PlanItemStatus.PENDING)
          .findFirst()
          .ifPresent(
              pi -> {
                pi.markRunning();
                registry.indexWorkerForCompletion(caseId, pi.getWorkerName(), pi.getPlanItemId());
              });
    }
  }
}
