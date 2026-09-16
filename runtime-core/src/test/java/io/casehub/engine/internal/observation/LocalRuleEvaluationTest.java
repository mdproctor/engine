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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.casehub.api.spi.observation.InterestLandscape;
import io.casehub.api.spi.observation.LocalRule;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.RuleAction;
import io.casehub.api.spi.observation.RuleCondition;
import io.casehub.api.spi.observation.RuleConfig;
import io.casehub.api.spi.observation.RuleContext;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalRuleEvaluationTest {

  private RuleRegistry ruleRegistry;
  private SignalRegistry signalRegistry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    ruleRegistry = new RuleRegistry();
    signalRegistry = new SignalRegistry();
    caseId = UUID.randomUUID();
  }

  private RuleContext contextWithObservations(List<Observation> observations) {
    return new RuleContext(
        observations,
        Map.of(),
        JsonNodeFactory.instance.objectNode(),
        Set.of(),
        InterestLandscape.EMPTY,
        "agent-1",
        "tenant-1",
        caseId);
  }

  private RuleContext emptyContext() {
    return contextWithObservations(List.of());
  }

  @Test
  void predicateConditionMatchesAndFiresDepositSignal() {
    var rule =
        new LocalRule(
            "r1",
            new RuleCondition.PredicateCondition(ctx -> !ctx.observations().isEmpty()),
            List.of(new RuleAction.DepositSignal("alert", 0.9, null)),
            0);

    var evaluator = new LocalRuleEvaluator(signalRegistry);
    var observations = List.of(new Observation("pattern-1", 0.8, Map.of(), Instant.now()));
    var ruleContext = contextWithObservations(observations);

    var firings = evaluator.evaluate("agent-1", List.of(rule), ruleContext, RuleConfig.defaults());

    assertEquals(1, firings.size());
    assertEquals("r1", firings.get(0).ruleId());
    var perceived = signalRegistry.perceive(caseId, 0.01);
    assertTrue(perceived.containsKey("alert"));
  }

  @Test
  void predicateConditionNoMatchNoFiring() {
    var rule =
        new LocalRule(
            "r1",
            new RuleCondition.PredicateCondition(ctx -> !ctx.observations().isEmpty()),
            List.of(new RuleAction.DepositSignal("alert", 0.9, null)),
            0);

    var evaluator = new LocalRuleEvaluator(signalRegistry);
    var firings =
        evaluator.evaluate("agent-1", List.of(rule), emptyContext(), RuleConfig.defaults());

    assertTrue(firings.isEmpty());
    assertTrue(signalRegistry.perceive(caseId, 0.01).isEmpty());
  }

  @Test
  void priorityDeterminesExecutionOrder() {
    var lowPriority =
        new LocalRule(
            "low",
            new RuleCondition.PredicateCondition(ctx -> true),
            List.of(new RuleAction.DepositSignal("low-signal", 0.3, null)),
            1);
    var highPriority =
        new LocalRule(
            "high",
            new RuleCondition.PredicateCondition(ctx -> true),
            List.of(new RuleAction.DepositSignal("high-signal", 0.9, null)),
            10);

    var evaluator = new LocalRuleEvaluator(signalRegistry);
    var firings =
        evaluator.evaluate(
            "agent-1", List.of(lowPriority, highPriority), emptyContext(), RuleConfig.defaults());

    assertEquals(2, firings.size());
    assertEquals("high", firings.get(0).ruleId());
    assertEquals("low", firings.get(1).ruleId());
  }

  @Test
  void writeContextActionCollected() {
    var rule =
        new LocalRule(
            "r1",
            new RuleCondition.PredicateCondition(ctx -> true),
            List.of(new RuleAction.WriteContext("status", new IntNode(42))),
            0);

    var evaluator = new LocalRuleEvaluator(signalRegistry);
    var firings =
        evaluator.evaluate("agent-1", List.of(rule), emptyContext(), RuleConfig.defaults());

    assertEquals(1, firings.size());
    var writeActions =
        firings.get(0).executedActions().stream()
            .filter(a -> a instanceof RuleAction.WriteContext)
            .toList();
    assertEquals(1, writeActions.size());
  }

  @Test
  void maxActionsPerCycleEnforced() {
    var rule =
        new LocalRule(
            "r1",
            new RuleCondition.PredicateCondition(ctx -> true),
            List.of(
                new RuleAction.DepositSignal("s1", 1.0, null),
                new RuleAction.DepositSignal("s2", 1.0, null),
                new RuleAction.DepositSignal("s3", 1.0, null)),
            0);

    var config = new RuleConfig(50, 2, 100);
    var evaluator = new LocalRuleEvaluator(signalRegistry);
    var firings = evaluator.evaluate("agent-1", List.of(rule), emptyContext(), config);

    long totalActions = firings.stream().mapToLong(f -> f.executedActions().size()).sum();
    assertTrue(totalActions <= 2);
  }

  @Test
  void failingConditionSkipsRule() {
    var rule =
        new LocalRule(
            "r1",
            new RuleCondition.PredicateCondition(
                ctx -> {
                  throw new RuntimeException("boom");
                }),
            List.of(new RuleAction.DepositSignal("never", 1.0, null)),
            0);

    var evaluator = new LocalRuleEvaluator(signalRegistry);
    var firings =
        evaluator.evaluate("agent-1", List.of(rule), emptyContext(), RuleConfig.defaults());

    assertTrue(firings.isEmpty());
  }

  @Test
  void allMatchingRulesFire() {
    var rule1 =
        new LocalRule(
            "r1",
            new RuleCondition.PredicateCondition(ctx -> true),
            List.of(new RuleAction.DepositSignal("s1", 1.0, null)),
            5);
    var rule2 =
        new LocalRule(
            "r2",
            new RuleCondition.PredicateCondition(ctx -> true),
            List.of(new RuleAction.DepositSignal("s2", 1.0, null)),
            5);

    var evaluator = new LocalRuleEvaluator(signalRegistry);
    var firings =
        evaluator.evaluate("agent-1", List.of(rule1, rule2), emptyContext(), RuleConfig.defaults());

    assertEquals(2, firings.size());
  }
}
