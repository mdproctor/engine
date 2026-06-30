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
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.spi.routing.ImplementationCandidate;
import io.casehub.api.spi.routing.ImplementationRoutingContext;
import io.casehub.api.spi.routing.ImplementationRoutingStrategy;
import io.casehub.api.spi.routing.ImplementationSelection;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.blackboard.stage.Stage;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.worker.api.Worker;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
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
  private final Map<String, PlanningStrategy> strategies;
  private final StageLifecycleEvaluator stageLifecycleEvaluator;
  private final Instance<BlackboardPlanConfigurer> configurers;
  private final ImplementationRoutingStrategy implementationRoutingStrategy;

  @Inject
  public PlanningStrategyLoopControl(
      BlackboardRegistry registry,
      Instance<PlanningStrategy> strategyBeans,
      StageLifecycleEvaluator stageLifecycleEvaluator,
      Instance<BlackboardPlanConfigurer> configurers,
      ImplementationRoutingStrategy implementationRoutingStrategy) {
    this.registry = registry;
    this.strategies =
        StreamSupport.stream(strategyBeans.spliterator(), false)
            .collect(Collectors.toMap(PlanningStrategy::getId, s -> s));
    this.stageLifecycleEvaluator = stageLifecycleEvaluator;
    this.configurers = configurers;
    this.implementationRoutingStrategy = implementationRoutingStrategy;
  }

  @Override
  public Uni<List<Binding>> select(PlanExecutionContext ctx, List<Binding> eligible) {
    CaseStatus status = ctx.caseStatus();
    if (status != CaseStatus.RUNNING && status != CaseStatus.WAITING) {
      return Uni.createFrom().item(List.of());
    }
    UUID caseId = ctx.caseId();
    CasePlanModel plan = registry.getOrCreate(caseId, ctx.tenancyId());

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

    return stageLifecycleEvaluator
        .evaluate(plan, ctx)
        .chain(() -> applyImplementationRouting(ctx, gatedEligible))
        .invoke(
            routed -> {
              // Create PlanItems only for surviving bindings — routing decision is upstream.
              // addPlanItemIfAbsent is atomic (no TOCTOU). Auto-register with owning stages
              // for autocomplete tracking. Refs casehubio/engine#497, engine#476.
              routed.forEach(
                  binding -> {
                    String workerName = resolveWorkerName(binding, ctx);
                    PlanItem item =
                        PlanItem.create(binding.getName(), workerName, 0, binding.target());
                    if (plan.addPlanItemIfAbsent(item)) {
                      registerWithOwningStages(plan, binding.getName(), item.getPlanItemId());
                    }
                  });
            })
        .chain(
            routed -> {
              String strategyId = ctx.definition().getPlanningStrategy();
              if (strategyId == null || strategyId.isEmpty()) {
                strategyId = "default";
              }
              PlanningStrategy strategy = strategies.get(strategyId);
              if (strategy == null) {
                strategy = strategies.get("default");
                if (strategy == null) {
                  throw new IllegalStateException(
                      "No default planning strategy found. Available: " + strategies.keySet());
                }
              }
              return strategy.select(plan, ctx, routed);
            })
        .map(selected -> filterToDispatchable(plan, selected))
        .invoke(dispatchable -> indexSelectedForCompletion(caseId, dispatchable, plan));
  }

  /**
   * Groups gated-eligible bindings by capability name. Groups with a single binding pass through
   * unchanged. Groups with multiple bindings consult {@link ImplementationRoutingStrategy} to
   * select which binding(s) survive. Non-capability bindings are never grouped. Refs
   * casehubio/engine#476.
   */
  private Uni<List<Binding>> applyImplementationRouting(
      PlanExecutionContext ctx, List<Binding> gatedEligible) {
    Map<String, List<Binding>> byCapability = new LinkedHashMap<>();
    List<Binding> nonCapability = new ArrayList<>();
    for (Binding b : gatedEligible) {
      if (b.target() instanceof CapabilityTarget ct) {
        byCapability.computeIfAbsent(ct.capability().name(), k -> new ArrayList<>()).add(b);
      } else {
        nonCapability.add(b);
      }
    }

    Uni<List<Binding>> result = Uni.createFrom().item(new ArrayList<>(nonCapability));
    for (var entry : byCapability.entrySet()) {
      String capName = entry.getKey();
      List<Binding> group = entry.getValue();
      if (group.size() == 1) {
        result = result.invoke(acc -> acc.addAll(group));
      } else {
        result =
            result.chain(
                acc -> {
                  List<ImplementationCandidate> candidates =
                      group.stream()
                          .map(
                              b ->
                                  new ImplementationCandidate(
                                      b.getName(), resolveWorkerName(b, ctx), capName))
                          .toList();
                  var routingCtx =
                      new ImplementationRoutingContext(
                          ctx.caseId(),
                          capName,
                          ctx.caseContext() != null ? ctx.caseContext().asJsonNode() : null);
                  return implementationRoutingStrategy
                      .select(routingCtx, candidates)
                      .map(
                          selection ->
                              switch (selection) {
                                case ImplementationSelection.Selected s -> {
                                  Set<String> kept = Set.copyOf(s.bindingNames());
                                  acc.addAll(
                                      group.stream()
                                          .filter(b -> kept.contains(b.getName()))
                                          .toList());
                                  yield acc;
                                }
                                case ImplementationSelection.RunAll ignored -> {
                                  acc.addAll(group);
                                  yield acc;
                                }
                                case ImplementationSelection.RunNone ignored -> acc;
                              });
                });
      }
    }
    return result;
  }

  /**
   * Filters out bindings whose PlanItems are already dispatched (RUNNING, DELEGATED, COMPLETED,
   * FAULTED, CANCELLED). Only PENDING PlanItems (first dispatch) are returned. This prevents
   * re-dispatch of in-flight or completed bindings on repeated CONTEXT_CHANGED evaluations,
   * regardless of whether the case is RUNNING or WAITING.
   *
   * <p>Pre-existing timing race (engine#364): a second CONTEXT_CHANGED arriving before a
   * HumanTask/SubCase handler marks its PlanItem DELEGATED will find it still PENDING and
   * re-dispatch. This filter prevents re-dispatch *after* the handler runs, not *before*.
   */
  private List<Binding> filterToDispatchable(CasePlanModel plan, List<Binding> selected) {
    return selected.stream()
        .filter(
            b ->
                plan.getPlanItemByBindingName(b.getName())
                    .map(pi -> pi.getStatus() == PlanItemStatus.PENDING)
                    .orElse(true))
        .toList();
  }

  /**
   * Resolves the worker name for a Binding by matching its capability against the case definition's
   * worker list. Returns the capability name as fallback if no worker matches.
   */
  private String resolveWorkerName(Binding binding, PlanExecutionContext ctx) {
    return switch (binding.target()) {
      case null -> "unknown";
      case io.casehub.api.model.CapabilityTarget ct -> {
        String capName = ct.capability().name();
        List<Worker> matching =
            ctx.definition().getWorkers().stream()
                .filter(w -> w.capabilityNames() != null && w.capabilityNames().contains(capName))
                .toList();
        if (matching.size() > 1) {
          LOG.warnf(
              "Capability '%s' matched %d workers — only '%s' will be tracked for PlanItem completion. "
                  + "Workers [%s] will fire but their completion events will be silently ignored, "
                  + "leaving their PlanItems RUNNING indefinitely. "
                  + "Multi-worker fan-out requires per-worker PlanItems (casehubio/engine#82).",
              capName,
              matching.size(),
              matching.get(0).name(),
              matching.stream()
                  .skip(1)
                  .map(Worker::name)
                  .collect(java.util.stream.Collectors.joining(", ")));
        }
        yield matching.isEmpty() ? capName : matching.get(0).name();
      }
      case SubCaseTarget st -> "unknown";
      case HumanTaskTarget ht -> "unknown";
      case ExtensionTarget et -> "unknown";
    };
  }

  /**
   * For each selected Binding, marks its PlanItem RUNNING and registers the worker-name →
   * planItemId mapping for completion tracking — but only for CapabilityTarget bindings.
   *
   * <p>HumanTaskTarget, SubCaseTarget, and ExtensionTarget bindings are skipped here because the
   * handler that processes the dispatched event owns the RUNNING transition for those target types:
   * the transition must not happen until the handler has successfully completed its work (WorkItem
   * creation, subcase start, etc.). Protocol PP-20260517-cbf836 — PlanItem must not be marked
   * RUNNING until all resolution steps succeed. Refs engine#312.
   */
  private void indexSelectedForCompletion(UUID caseId, List<Binding> selected, CasePlanModel plan) {
    for (Binding binding : selected) {
      switch (binding.target()) {
        case CapabilityTarget ignored -> {
          /* only capability bindings — proceed below */
        }
        case null, default -> {
          continue;
        } // handler owns the RUNNING transition for all other target types
      }
      plan.getAgenda().stream()
          .filter(
              pi ->
                  pi.getBindingName().equals(binding.getName())
                      && pi.getStatus() == PlanItemStatus.PENDING)
          .findFirst()
          .ifPresent(
              pi -> {
                pi.markRunning();
                registry.indexForCompletion(caseId, pi.getWorkerName(), pi.getPlanItemId());
              });
    }
  }

  /**
   * Registers a newly created PlanItem with all stages that declare ownership of its binding name
   * via {@link Stage#getContainedBindingNames()}. Enables {@link
   * io.casehub.blackboard.handler.StageAutocompleteEvaluator} to detect when all required items in
   * a stage have reached terminal states. Refs casehubio/engine#497.
   */
  private void registerWithOwningStages(CasePlanModel plan, String bindingName, String planItemId) {
    for (Stage stage : plan.getAllStages()) {
      if (stage.getContainedBindingNames().contains(bindingName)) {
        stage.addPlanItem(planItemId);
        stage.addRequiredItem(planItemId);
      }
    }
  }
}
