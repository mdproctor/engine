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
package io.casehub.api.spi;

import io.casehub.api.spi.RiskDecision.Autonomous;
import io.casehub.api.spi.RiskDecision.GateRequired;
import io.casehub.api.spi.routing.CandidateSetStrategy;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.worker.api.PlannedAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.stream.StreamSupport;
import org.jboss.logging.Logger;

/**
 * Chains all {@link RiskClassifier @RiskClassifier}-qualified {@link ActionRiskClassifier} beans
 * and returns the most restrictive {@link RiskDecision}.
 *
 * <p>When no consumer has registered any {@code @RiskClassifier} classifier, the injection point is
 * unsatisfied and the method returns {@link Autonomous} immediately.
 *
 * <p>If any classifier throws, the fail-safe {@link GateRequired} is returned — the action is gated
 * for manual review.
 *
 * <p>"Most restrictive" = fewest {@code candidateGroups}; tie → shorter {@code expiresIn}; tie →
 * CDI iteration order (first wins).
 */
@ApplicationScoped
public class ChainedActionRiskClassifier implements ActionRiskClassifier {

  private static final Logger LOG = Logger.getLogger(ChainedActionRiskClassifier.class);

  static final GateRequired FAIL_SAFE =
      new GateRequired(
          "Classifier error — manual review required before proceeding",
          true,
          null,
          null,
          null,
          null,
          null);

  @Inject @RiskClassifier Instance<ActionRiskClassifier> classifiers;

  @Override
  public RiskDecision classify(final PlannedAction action, final ClassificationContext context) {
    if (classifiers.isUnsatisfied()) {
      return new Autonomous();
    }

    try {
      return StreamSupport.stream(classifiers.spliterator(), false)
          .map(c -> c.classify(action, context))
          .reduce((RiskDecision) new Autonomous(), this::mostRestrictive);
    } catch (final Exception e) {
      LOG.errorf(
          e,
          "ActionRiskClassifier threw for action type='%s' workerId='%s'"
              + " caseId=%s — applying fail-safe GateRequired",
          action.actionType(),
          context.workerId(),
          context.caseId());
      return FAIL_SAFE;
    }
  }

  RiskDecision mostRestrictive(final RiskDecision a, final RiskDecision b) {
    if (!(b instanceof GateRequired gb)) return a;
    if (!(a instanceof GateRequired ga)) return b;
    return narrower(ga, gb);
  }

  private GateRequired narrower(final GateRequired a, final GateRequired b) {
    final boolean aHasQuorum = a.quorum() != null;
    final boolean bHasQuorum = b.quorum() != null;
    if (aHasQuorum != bHasQuorum) {
      return aHasQuorum ? a : b;
    }
    if (aHasQuorum) {
      if (a.quorum().required() != b.quorum().required()) {
        return a.quorum().required() > b.quorum().required() ? a : b;
      }
      if (a.quorum().instances() != b.quorum().instances()) {
        return a.quorum().instances() < b.quorum().instances() ? a : b;
      }
    }
    final int sizeA = candidateSetSize(a.candidateGroups());
    final int sizeB = candidateSetSize(b.candidateGroups());
    if (sizeA != sizeB) {
      return sizeA < sizeB ? a : b;
    }
    if (a.expiresIn() != null && b.expiresIn() != null) {
      return a.expiresIn().compareTo(b.expiresIn()) <= 0 ? a : b;
    }
    if (a.expiresIn() != null) {
      return a;
    }
    if (b.expiresIn() != null) {
      return b;
    }
    return a;
  }

  private int candidateSetSize(final CandidateSetStrategy strategy) {
    if (strategy == null) return Integer.MAX_VALUE;
    if (strategy instanceof StaticSetStrategy staticStrategy) {
      return staticStrategy.values().size();
    }
    return Integer.MAX_VALUE;
  }
}
