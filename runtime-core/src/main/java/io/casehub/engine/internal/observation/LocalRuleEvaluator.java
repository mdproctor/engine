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
package io.casehub.engine.internal.observation;

import io.casehub.api.spi.observation.LocalRule;
import io.casehub.api.spi.observation.RuleAction;
import io.casehub.api.spi.observation.RuleCondition;
import io.casehub.api.spi.observation.RuleConfig;
import io.casehub.api.spi.observation.RuleContext;
import io.casehub.api.spi.observation.RuleFiring;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jboss.logging.Logger;

public class LocalRuleEvaluator {

  private static final Logger LOG = Logger.getLogger(LocalRuleEvaluator.class);
  private static final Duration DEFAULT_HALF_LIFE = Duration.ofMinutes(5);
  private static final int DEFAULT_MAX_SIGNALS = 100;

  private final SignalRegistry signalRegistry;

  public LocalRuleEvaluator(SignalRegistry signalRegistry) {
    this.signalRegistry = signalRegistry;
  }

  public List<RuleFiring> evaluate(
      String agentId, List<LocalRule> rules, RuleContext context, RuleConfig config) {
    List<LocalRule> sorted =
        rules.stream().sorted(Comparator.comparingInt(LocalRule::priority).reversed()).toList();

    List<RuleFiring> firings = new ArrayList<>();
    int totalActions = 0;

    for (LocalRule rule : sorted) {
      if (totalActions >= config.maxActionsPerCycle()) {
        break;
      }

      boolean matches = evaluateCondition(rule.condition(), context, rule.id());
      if (!matches) {
        continue;
      }

      List<RuleAction> executed = new ArrayList<>();
      for (RuleAction action : rule.actions()) {
        if (totalActions >= config.maxActionsPerCycle()) {
          break;
        }
        executeCoordinationAction(action, context, agentId);
        executed.add(action);
        totalActions++;
      }

      if (!executed.isEmpty()) {
        firings.add(new RuleFiring(rule.id(), List.copyOf(executed), Instant.now()));
      }
    }

    return firings;
  }

  private boolean evaluateCondition(RuleCondition condition, RuleContext context, String ruleId) {
    return switch (condition) {
      case RuleCondition.PredicateCondition pc -> {
        try {
          yield pc.predicate().test(context);
        } catch (Exception e) {
          LOG.warnf(e, "Rule condition failed for rule=%s, skipping", ruleId);
          yield false;
        }
      }
      case RuleCondition.ExpressionCondition ec -> {
        LOG.debugf("Expression condition evaluation not yet wired for rule=%s, skipping", ruleId);
        yield false;
      }
    };
  }

  private void executeCoordinationAction(RuleAction action, RuleContext context, String agentId) {
    switch (action) {
      case RuleAction.DepositSignal ds ->
          signalRegistry.deposit(
              context.caseId(),
              ds.name(),
              ds.strength(),
              ds.halfLife() != null ? ds.halfLife() : DEFAULT_HALF_LIFE,
              agentId,
              DEFAULT_MAX_SIGNALS);
      case RuleAction.WriteContext wc -> {} // collected by caller, applied after all rules
      case RuleAction.RegisterInterest ri -> {} // deferred to handler context
      case RuleAction.DeregisterInterest di -> {} // deferred to handler context
    }
  }
}
