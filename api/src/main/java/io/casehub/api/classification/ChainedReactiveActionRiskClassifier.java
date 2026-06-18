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
package io.casehub.api.classification;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.PlannedAction;
import io.casehub.api.spi.ReactiveActionRiskClassifier;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.RiskDecision.Autonomous;
import io.casehub.api.spi.RiskDecision.GateRequired;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.stream.StreamSupport;
import org.jboss.logging.Logger;

/**
 * Chains all {@link RiskClassifier}-qualified {@link ActionRiskClassifier} and {@link
 * ReactiveActionRiskClassifier} beans and returns the most restrictive {@link RiskDecision}.
 *
 * <p>When no consumer has registered any {@code @RiskClassifier} classifier (blocking or reactive),
 * both injection points are unsatisfied and the method returns {@link Autonomous} immediately.
 *
 * <p>Blocking classifiers are offloaded to the worker thread pool via {@link
 * Infrastructure#getDefaultWorkerPool()}. Reactive classifiers run natively on the caller thread.
 * Results from both paths are merged via most-restrictive-wins.
 *
 * <p>If any classifier throws, the fail-safe {@link GateRequired} is returned — the action is gated
 * for manual review.
 *
 * <p>"Most restrictive" = fewest {@code candidateGroups}; tie → shorter {@code expiresIn}; tie →
 * CDI iteration order (first wins). Union semantics are intentionally rejected.
 */
@ApplicationScoped
public class ChainedReactiveActionRiskClassifier implements ReactiveActionRiskClassifier {

  private static final Logger LOG = Logger.getLogger(ChainedReactiveActionRiskClassifier.class);

  static final GateRequired FAIL_SAFE =
      new GateRequired(
          "Classifier error — manual review required before proceeding", true, null, null, null);

  @Inject @RiskClassifier Instance<ActionRiskClassifier> classifiers;

  @Inject @RiskClassifier Instance<ReactiveActionRiskClassifier> reactiveClassifiers;

  @Override
  public Uni<RiskDecision> classify(final PlannedAction action) {
    final boolean noBlocking = classifiers.isUnsatisfied();
    final boolean noReactive = reactiveClassifiers.isUnsatisfied();
    if (noBlocking && noReactive) {
      return Uni.createFrom().item(new Autonomous());
    }

    final Uni<RiskDecision> blockingResult =
        noBlocking
            ? Uni.createFrom().item(new Autonomous())
            : Uni.createFrom()
                .item(
                    () -> {
                      try {
                        return StreamSupport.stream(classifiers.spliterator(), false)
                            .map(c -> c.classify(action))
                            .reduce(
                                (RiskDecision) new Autonomous(),
                                ChainedReactiveActionRiskClassifier.this::mostRestrictive);
                      } catch (final Exception e) {
                        LOG.errorf(
                            e,
                            "ActionRiskClassifier threw for action type='%s' workerId='%s'"
                                + " caseId=%s — applying fail-safe GateRequired",
                            action.actionType(),
                            action.workerId(),
                            action.caseId());
                        return (RiskDecision) FAIL_SAFE;
                      }
                    })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());

    if (noReactive) {
      return blockingResult;
    }

    final List<Uni<RiskDecision>> reactiveUnis;
    try {
      reactiveUnis =
          StreamSupport.stream(reactiveClassifiers.spliterator(), false)
              .map(
                  c ->
                      c.classify(action)
                          .onFailure()
                          .recoverWithItem(
                              t -> {
                                LOG.errorf(
                                    t,
                                    "ReactiveActionRiskClassifier threw for action"
                                        + " type='%s' — applying fail-safe GateRequired",
                                    action.actionType());
                                return FAIL_SAFE;
                              }))
              .toList();
    } catch (final Exception e) {
      LOG.errorf(
          e,
          "ReactiveActionRiskClassifier threw synchronously for action type='%s'"
              + " — applying fail-safe GateRequired",
          action.actionType());
      return Uni.createFrom().item((RiskDecision) FAIL_SAFE);
    }

    final Uni<RiskDecision> reactiveResult =
        Uni.join()
            .all(reactiveUnis)
            .andFailFast()
            .map(results -> results.stream().reduce(new Autonomous(), this::mostRestrictive));

    return Uni.combine().all().unis(blockingResult, reactiveResult).with(this::mostRestrictive);
  }

  RiskDecision mostRestrictive(final RiskDecision a, final RiskDecision b) {
    if (!(b instanceof GateRequired gb)) return a;
    if (!(a instanceof GateRequired ga)) return b;
    return narrower(ga, gb);
  }

  private GateRequired narrower(final GateRequired a, final GateRequired b) {
    final int sizeA = a.candidateGroups() == null ? Integer.MAX_VALUE : a.candidateGroups().size();
    final int sizeB = b.candidateGroups() == null ? Integer.MAX_VALUE : b.candidateGroups().size();
    if (sizeA != sizeB) return sizeA < sizeB ? a : b;
    if (a.expiresIn() != null && b.expiresIn() != null) {
      return a.expiresIn().compareTo(b.expiresIn()) <= 0 ? a : b;
    }
    if (a.expiresIn() != null) return a;
    if (b.expiresIn() != null) return b;
    return a;
  }
}
