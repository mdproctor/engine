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
package io.casehub.engine.internal.worker;

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
import java.util.stream.StreamSupport;
import org.jboss.logging.Logger;

/**
 * Chains all {@link RiskClassifier}-qualified {@link ActionRiskClassifier} beans and returns the
 * most restrictive {@link RiskDecision}.
 *
 * <p>When no consumer has registered a {@code @RiskClassifier} classifier, {@link #isUnsatisfied}
 * returns true and the method returns {@link Autonomous} immediately — the chain IS the default.
 *
 * <p>Blocking classifiers (DB queries, external API calls) are offloaded to the worker thread pool
 * via {@link Infrastructure#getDefaultWorkerPool()} to avoid blocking the Vert.x IO thread.
 *
 * <p>If any classifier throws, the fail-safe {@link GateRequired} is returned immediately — the
 * action is gated for manual review. Fail-safe is required for AML/clinical compliance: a
 * classifier failure must not permit a consequential action to proceed autonomously.
 *
 * <p>"Most restrictive" = fewest {@code candidateGroups}; tie → shorter {@code expiresIn}; tie →
 * CDI iteration order (first wins). Union semantics are intentionally rejected: ["mlro"] ∪
 * ["physician"] = ["mlro", "physician"] would allow a physician to approve a SAR filing they have
 * no authority over.
 */
@ApplicationScoped
public class ChainedReactiveActionRiskClassifier implements ReactiveActionRiskClassifier {

  private static final Logger LOG = Logger.getLogger(ChainedReactiveActionRiskClassifier.class);

  static final GateRequired FAIL_SAFE =
      new GateRequired(
          "Classifier error — manual review required before proceeding", true, null, null, null);

  @Inject @RiskClassifier Instance<ActionRiskClassifier> classifiers;

  @Override
  public Uni<RiskDecision> classify(final PlannedAction action) {
    if (classifiers.isUnsatisfied()) {
      return Uni.createFrom().item(new Autonomous());
    }
    return Uni.createFrom()
        .item(
            () -> {
              try {
                return StreamSupport.stream(classifiers.spliterator(), false)
                    .map(c -> c.classify(action))
                    .reduce(new Autonomous(), this::mostRestrictive);
              } catch (final Exception e) {
                LOG.errorf(
                    e,
                    "ActionRiskClassifier threw for action type='%s' workerId='%s' caseId=%s — "
                        + "applying fail-safe GateRequired",
                    action.actionType(),
                    action.workerId(),
                    action.caseId());
                return FAIL_SAFE;
              }
            })
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
  }

  private RiskDecision mostRestrictive(final RiskDecision a, final RiskDecision b) {
    if (!(b instanceof GateRequired gb)) return a;
    if (!(a instanceof GateRequired ga)) return b;
    return narrower(ga, gb);
  }

  /**
   * Returns whichever gate is more restrictive (narrows the set of eligible approvers).
   *
   * <p>Fewer {@code candidateGroups} = more restrictive (a non-null group list with N members beats
   * null, which means "no restriction" = {@link Integer#MAX_VALUE} effective groups).
   */
  private GateRequired narrower(final GateRequired a, final GateRequired b) {
    final int sizeA = a.candidateGroups() == null ? Integer.MAX_VALUE : a.candidateGroups().size();
    final int sizeB = b.candidateGroups() == null ? Integer.MAX_VALUE : b.candidateGroups().size();
    if (sizeA != sizeB) return sizeA < sizeB ? a : b;
    // Equal group count: a deadline is more restrictive than no deadline; shorter beats longer.
    if (a.expiresIn() != null && b.expiresIn() != null) {
      return a.expiresIn().compareTo(b.expiresIn()) <= 0 ? a : b;
    }
    if (a.expiresIn() != null) return a;
    if (b.expiresIn() != null) return b;
    return a; // tie → CDI iteration order (first wins)
  }
}
