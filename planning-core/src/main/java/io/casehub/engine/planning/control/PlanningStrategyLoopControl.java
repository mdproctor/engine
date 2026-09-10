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
package io.casehub.engine.planning.control;

import io.casehub.api.engine.LoopControl;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.routing.ImplementationCandidate;
import io.casehub.api.spi.routing.ImplementationRoutingContext;
import io.casehub.api.spi.routing.ImplementationRoutingStrategy;
import io.casehub.api.spi.routing.ImplementationSelection;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PlanningStrategyLoopControl implements LoopControl {

  private static final Logger LOG = Logger.getLogger(PlanningStrategyLoopControl.class);

  private final BlackboardRegistry registry;
  private final CompoundLifecycleEvaluator compoundLifecycleEvaluator;
  private final CompoundStrategyDispatcher compoundDispatcher;
  private final Instance<BlackboardPlanConfigurer> configurers;
  private final ImplementationRoutingStrategy implementationRoutingStrategy;
  private final io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry;

  @Inject
  public PlanningStrategyLoopControl(
      BlackboardRegistry registry,
      CompoundLifecycleEvaluator compoundLifecycleEvaluator,
      CompoundStrategyDispatcher compoundDispatcher,
      Instance<BlackboardPlanConfigurer> configurers,
      ImplementationRoutingStrategy implementationRoutingStrategy,
      io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry) {
    this.registry = registry;
    this.compoundLifecycleEvaluator = compoundLifecycleEvaluator;
    this.compoundDispatcher = compoundDispatcher;
    this.configurers = configurers;
    this.implementationRoutingStrategy = implementationRoutingStrategy;
    this.expressionEngineRegistry = expressionEngineRegistry;
  }

  @Override
  public List<Binding> select(PlanExecutionContext ctx, List<Binding> eligible) {
    CaseStatus status = ctx.caseStatus();
    if (status != CaseStatus.RUNNING && status != CaseStatus.WAITING) {
      return List.of();
    }
    UUID caseId = ctx.caseId();
    CasePlanModel plan = registry.getOrCreate(caseId, ctx.tenancyId());

    boolean isFirstCall = registry.markConfigured(caseId);
    if (isFirstCall) {
      configurers.stream()
          .filter(c -> c.supports(ctx.definition()))
          .forEach(c -> c.configure(plan, ctx));
      validateCompoundScopedBindings(ctx.definition(), plan);
    }

    var activatedCompounds = compoundLifecycleEvaluator.evaluate(plan, ctx);

    List<Binding> scopeActivated =
        collectScopeActivatedBindings(ctx, plan, activatedCompounds, isFirstCall);

    List<Binding> allEligible = new ArrayList<>(eligible);
    allEligible.addAll(scopeActivated);

    Set<String> allScopedNames =
        plan.getAllCompounds().stream()
            .flatMap(c -> c.scopedBindings().keySet().stream())
            .collect(Collectors.toSet());

    Set<String> activeScopedNames =
        plan.getCompoundsByStatus(TaskStatus.RUNNING).stream()
            .flatMap(c -> c.scopedBindings().keySet().stream())
            .collect(Collectors.toSet());

    List<Binding> gatedEligible =
        allScopedNames.isEmpty()
            ? allEligible
            : allEligible.stream()
                .filter(
                    b ->
                        !allScopedNames.contains(b.getName())
                            || activeScopedNames.contains(b.getName()))
                .collect(Collectors.toList());

    List<Binding> routed = applyImplementationRouting(ctx, gatedEligible);

    routed.forEach(
        binding -> {
          io.casehub.api.model.ExecutorRef executor = resolveExecutor(binding, ctx);
          PlanItem item = PlanItem.create(binding.getName(), executor, 0, binding.target());
          plan.addPlanItemIfAbsent(item);
        });

    List<Binding> selected = compoundDispatcher.dispatch(plan, ctx, routed);

    return filterAndIndexForDispatch(caseId, plan, selected, ctx);
  }

  private List<Binding> collectScopeActivatedBindings(
      PlanExecutionContext ctx,
      CasePlanModel plan,
      List<io.casehub.engine.planning.plan.PlanItemDefinition.Compound> activatedCompounds,
      boolean isFirstCall) {
    List<Binding> result = new ArrayList<>();
    List<Binding> allBindings = ctx.definition().getBindings();
    if (allBindings == null || allBindings.isEmpty()) {
      return result;
    }

    if (isFirstCall) {
      for (Binding b : allBindings) {
        if (b.getOn() instanceof io.casehub.api.model.ScopeActivatedTrigger
            && b.lifecycleScope() == io.casehub.api.model.LifecycleScope.CASE) {
          if (evaluateWhenGuard(b, ctx)) {
            result.add(b);
          }
        }
      }
    }

    for (var compound : activatedCompounds) {
      Set<String> scopedNames = compound.scopedBindings().keySet();
      for (Binding b : allBindings) {
        if (b.getOn() instanceof io.casehub.api.model.ScopeActivatedTrigger
            && scopedNames.contains(b.getName())) {
          if (evaluateWhenGuard(b, ctx)) {
            result.add(b);
          }
        }
      }
    }

    return result;
  }

  private boolean evaluateWhenGuard(Binding binding, PlanExecutionContext ctx) {
    if (binding.getWhen() == null) {
      return true;
    }
    if (expressionEngineRegistry == null) {
      return true;
    }
    return expressionEngineRegistry.evaluate(binding.getWhen(), ctx.caseContext());
  }

  private void validateCompoundScopedBindings(
      io.casehub.api.model.CaseDefinition definition, CasePlanModel plan) {
    Set<String> allScopedNames =
        plan.getAllCompounds().stream()
            .flatMap(c -> c.scopedBindings().keySet().stream())
            .collect(Collectors.toSet());
    for (Binding b : definition.getBindings()) {
      if (b.getOn() instanceof io.casehub.api.model.ScopeActivatedTrigger
          && b.lifecycleScope() == io.casehub.api.model.LifecycleScope.COMPOUND
          && !allScopedNames.contains(b.getName())) {
        throw new IllegalStateException(
            "Binding '"
                + b.getName()
                + "' has ScopeActivatedTrigger with COMPOUND scope "
                + "but is not in any compound's scopedBindings(). "
                + "Add it to a Compound.builder().binding(\""
                + b.getName()
                + "\") call.");
      }
    }
  }

  private List<Binding> applyImplementationRouting(
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

    List<Binding> result = new ArrayList<>(nonCapability);
    for (var entry : byCapability.entrySet()) {
      String capName = entry.getKey();
      List<Binding> group = entry.getValue();
      if (group.size() == 1) {
        result.addAll(group);
      } else {
        List<ImplementationCandidate> candidates =
            group.stream()
                .map(
                    b ->
                        new ImplementationCandidate(
                            b.getName(), resolveExecutor(b, ctx).name(), capName))
                .toList();
        var routingCtx =
            new ImplementationRoutingContext(
                ctx.caseId(),
                capName,
                ctx.caseContext() != null ? ctx.caseContext().asJsonNode() : null,
                ctx.tenancyId(),
                ctx.experiences());
        ImplementationSelection selection =
            implementationRoutingStrategy.select(routingCtx, candidates);
        switch (selection) {
          case ImplementationSelection.Selected s -> {
            Set<String> kept = Set.copyOf(s.bindingNames());
            result.addAll(group.stream().filter(b -> kept.contains(b.getName())).toList());
          }
          case ImplementationSelection.RunAll ignored -> result.addAll(group);
          case ImplementationSelection.RunNone ignored -> {}
        }
      }
    }
    return result;
  }

  private List<Binding> filterAndIndexForDispatch(
      UUID caseId, CasePlanModel plan, List<Binding> selected, PlanExecutionContext ctx) {
    List<Binding> dispatched = new ArrayList<>();
    for (Binding binding : selected) {
      Optional<PlanItem> piOpt = plan.findPlanItemByBindingName(binding.getName());
      if (piOpt.isEmpty()) {
        dispatched.add(binding);
        continue;
      }
      PlanItem pi = piOpt.get();
      if (pi.getStatus().isTerminal()) {
        if (ctx.caseStatus() != CaseStatus.RUNNING) {
          dispatched.add(binding);
        }
        continue;
      }
      if (binding.target() instanceof CapabilityTarget) {
        io.casehub.api.model.LifecycleScope ls = binding.lifecycleScope();
        if ((ls == io.casehub.api.model.LifecycleScope.COMPOUND
                || ls == io.casehub.api.model.LifecycleScope.CASE)
            && pi.getStatus() == TaskStatus.RUNNING) {
          dispatched.add(binding);
        } else if (pi.tryMarkRunning()) {
          registry.indexForCompletion(caseId, pi.executorName(), pi.id());
          dispatched.add(binding);
        }
      } else {
        if (pi.tryMarkDispatching()) {
          dispatched.add(binding);
        }
      }
    }
    return dispatched;
  }

  private io.casehub.api.model.ExecutorRef resolveExecutor(
      Binding binding, PlanExecutionContext ctx) {
    var resolved =
        io.casehub.engine.common.internal.routing.BindingExecutorResolver.resolve(
            binding, ctx.definition());
    if (binding.target() instanceof CapabilityTarget ct) {
      String capName = ct.capability().name();
      long matchCount =
          ctx.definition().getWorkers().stream()
              .filter(w -> w.capabilities() != null && w.capabilities().contains(capName))
              .count();
      if (matchCount > 1) {
        LOG.warnf(
            "Capability '%s' matched %d workers — only '%s' will be tracked for PlanItem completion. "
                + "Multi-worker fan-out requires per-worker PlanItems (casehubio/engine#82).",
            capName, matchCount, resolved.name());
      }
    }
    return resolved;
  }
}
