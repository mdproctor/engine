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

import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.routing.ContextConstraint;
import io.casehub.api.model.routing.WorkloadConstraint;
import io.casehub.api.spi.routing.HumanTaskCandidates;
import io.casehub.api.spi.routing.HumanTaskRoutingContext;
import io.casehub.api.spi.routing.HumanTaskRoutingResult;
import io.casehub.api.spi.routing.HumanTaskRoutingStrategy;
import io.casehub.api.spi.routing.WorkloadDataProvider;
import io.casehub.api.spi.routing.WorkloadSnapshot;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Constraint-based humanTask routing strategy that uses declarative rules for candidate filtering
 * and scoring. Supports both context-driven conditions (global, evaluated once against case state)
 * and workload/fairness balancing (per-candidate, evaluated against operational state).
 *
 * <p>Unlike {@link CbrHumanTaskRoutingStrategy} which only enriches, this strategy CAN filter
 * candidates (via Exclude and maxActiveTaskCount) and CAN escalate (when all candidates are
 * excluded).
 *
 * <p>Resolved via {@code StrategyResolver} when {@code CaseDefinition.getHumanTaskRouting()}
 * returns {@code "constraint"}. Refs casehubio/engine#755.
 */
@ApplicationScoped
@Unremovable
public class ConstraintHumanTaskRoutingStrategy implements HumanTaskRoutingStrategy {

  private static final System.Logger LOG =
      System.getLogger(ConstraintHumanTaskRoutingStrategy.class.getName());

  private final ExpressionEngineRegistry expressionRegistry;
  private final WorkloadDataProvider workloadProvider;

  @Inject
  public ConstraintHumanTaskRoutingStrategy(
      ExpressionEngineRegistry expressionRegistry, WorkloadDataProvider workloadProvider) {
    this.expressionRegistry = expressionRegistry;
    this.workloadProvider = workloadProvider;
  }

  @Override
  public String id() {
    return "constraint";
  }

  @Override
  public HumanTaskRoutingResult select(
      final HumanTaskRoutingContext context, final HumanTaskCandidates candidates) {
    final var definition = context.caseDefinition();
    if (definition == null) {
      return new HumanTaskRoutingResult.Unchanged();
    }

    final var contextConstraints = definition.getHumanTaskContextConstraints();
    final var workloadConstraint = definition.getHumanTaskWorkloadConstraint();

    if (contextConstraints.isEmpty() && workloadConstraint == null) {
      return new HumanTaskRoutingResult.Unchanged();
    }

    final Set<String> eligibleUsers = new LinkedHashSet<>(candidates.users());
    final Map<String, Double> scores = new HashMap<>();

    applyContextConstraints(contextConstraints, context, eligibleUsers, scores);

    if (eligibleUsers.isEmpty()) {
      return new HumanTaskRoutingResult.Escalated("all candidates excluded by context constraints");
    }

    if (workloadConstraint != null) {
      applyWorkloadConstraint(workloadConstraint, eligibleUsers, scores, context.tenancyId());

      if (eligibleUsers.isEmpty()) {
        return new HumanTaskRoutingResult.Escalated(
            "all candidates excluded by workload constraints");
      }
    }

    scores.keySet().retainAll(eligibleUsers);

    final boolean usersChanged = !eligibleUsers.equals(candidates.users());
    if (!usersChanged && scores.isEmpty()) {
      return new HumanTaskRoutingResult.Unchanged();
    }

    return new HumanTaskRoutingResult.Enriched(candidates.groups(), eligibleUsers, scores);
  }

  private void applyContextConstraints(
      final java.util.List<ContextConstraint> constraints,
      final HumanTaskRoutingContext context,
      final Set<String> eligibleUsers,
      final Map<String, Double> scores) {
    for (final ContextConstraint constraint : constraints) {
      boolean match;
      try {
        match = expressionRegistry.evaluate(constraint.condition(), context.caseContext());
      } catch (final Exception e) {
        LOG.log(
            System.Logger.Level.WARNING,
            "Constraint condition evaluation failed — treating as false",
            e);
        match = false;
      }

      if (!match) {
        continue;
      }

      switch (constraint.effect()) {
        case ContextConstraint.Exclude exclude -> {
          eligibleUsers.removeAll(exclude.users());
        }
        case ContextConstraint.Prefer prefer -> {
          for (final String user : prefer.users()) {
            if (eligibleUsers.contains(user)) {
              scores.merge(user, constraint.weight(), Double::sum);
            }
          }
        }
      }
    }
  }

  private void applyWorkloadConstraint(
      final WorkloadConstraint constraint,
      final Set<String> eligibleUsers,
      final Map<String, Double> scores,
      final String tenancyId) {
    final Map<String, WorkloadSnapshot> workload =
        workloadProvider.getWorkload(eligibleUsers, tenancyId);

    if (workload.isEmpty()) {
      return;
    }

    if (constraint.maxActiveTaskCount() != null) {
      final int threshold = constraint.maxActiveTaskCount();
      eligibleUsers.removeIf(
          user -> {
            final WorkloadSnapshot snapshot = workload.get(user);
            return snapshot != null && snapshot.activeTaskCount() > threshold;
          });
    }

    if (constraint.loadBalanceWeight() != null && !eligibleUsers.isEmpty()) {
      final double weight = constraint.loadBalanceWeight();
      int maxCount = 0;
      for (final String user : eligibleUsers) {
        final WorkloadSnapshot snapshot = workload.get(user);
        if (snapshot != null && snapshot.activeTaskCount() > maxCount) {
          maxCount = snapshot.activeTaskCount();
        }
      }

      if (maxCount > 0) {
        for (final String user : eligibleUsers) {
          final WorkloadSnapshot snapshot = workload.get(user);
          final int count = snapshot != null ? snapshot.activeTaskCount() : 0;
          final double loadScore = weight * (1.0 - ((double) count / maxCount));
          scores.merge(user, loadScore, Double::sum);
        }
      }
    }
  }
}
