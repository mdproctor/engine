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
package io.casehub.api.spi.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.TextNode;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class LocalRuleTest {

  @Test
  void depositSignalAction() {
    var action = new RuleAction.DepositSignal("danger", 0.8, null);
    assertEquals("danger", action.name());
    assertEquals(0.8, action.strength());
    assertNull(action.halfLife());
  }

  @Test
  void depositSignalActionWithHalfLife() {
    var action = new RuleAction.DepositSignal("danger", 0.8, Duration.ofMinutes(5));
    assertEquals(Duration.ofMinutes(5), action.halfLife());
  }

  @Test
  void writeContextAction() {
    var action = new RuleAction.WriteContext("status", new TextNode("alert"));
    assertEquals("status", action.key());
    assertEquals("alert", action.value().asText());
  }

  @Test
  void registerInterestAction() {
    var interest =
        new InterestDeclaration.KeyThreshold(
            "temperature", InterestDeclaration.ComparisonOperator.GT, 100.0);
    var action = new RuleAction.RegisterInterest(interest);
    assertEquals(interest, action.declaration());
  }

  @Test
  void deregisterInterestAction() {
    var action = new RuleAction.DeregisterInterest("interest-42");
    assertEquals("interest-42", action.interestId());
  }

  @Test
  void expressionCondition() {
    ExpressionEvaluator eval =
        new ExpressionEvaluator() {
          @Override
          public String type() {
            return "test";
          }

          @Override
          public String expression() {
            return ".signals.danger.strength > 0.5";
          }
        };
    var cond = new RuleCondition.ExpressionCondition(eval);
    assertEquals(eval, cond.evaluator());
  }

  @Test
  void predicateCondition() {
    Predicate<RuleContext> pred = ctx -> !ctx.observations().isEmpty();
    var cond = new RuleCondition.PredicateCondition(pred);
    assertEquals(pred, cond.predicate());
  }

  @Test
  void localRuleConstruction() {
    var condition = new RuleCondition.PredicateCondition(ctx -> true);
    var actions = List.<RuleAction>of(new RuleAction.DepositSignal("found", 1.0, null));
    var rule = new LocalRule("rule-1", condition, actions, 10);
    assertEquals("rule-1", rule.id());
    assertEquals(10, rule.priority());
    assertEquals(1, rule.actions().size());
  }

  @Test
  void localRuleActionsAreImmutable() {
    var actions = new java.util.ArrayList<RuleAction>();
    actions.add(new RuleAction.DepositSignal("a", 1.0, null));
    var rule =
        new LocalRule("rule-1", new RuleCondition.PredicateCondition(ctx -> true), actions, 0);
    assertThrows(
        UnsupportedOperationException.class,
        () -> rule.actions().add(new RuleAction.DepositSignal("b", 1.0, null)));
  }

  @Test
  void localRuleRejectsNullId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LocalRule(null, new RuleCondition.PredicateCondition(ctx -> true), List.of(), 0));
  }

  @Test
  void localRuleRejectsBlankId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LocalRule("  ", new RuleCondition.PredicateCondition(ctx -> true), List.of(), 0));
  }

  @Test
  void localRuleRejectsNullCondition() {
    assertThrows(IllegalArgumentException.class, () -> new LocalRule("r1", null, List.of(), 0));
  }

  @Test
  void ruleContextConstruction() {
    var ctx =
        new RuleContext(
            List.of(),
            Map.of(),
            null,
            Set.of(),
            InterestLandscape.EMPTY,
            "agent-1",
            "tenant-1",
            UUID.randomUUID());
    assertEquals("agent-1", ctx.agentId());
    assertTrue(ctx.observations().isEmpty());
  }

  @Test
  void ruleFiringConstruction() {
    var actions = List.<RuleAction>of(new RuleAction.DepositSignal("x", 0.5, null));
    var firing = new RuleFiring("rule-1", actions, Instant.now());
    assertEquals("rule-1", firing.ruleId());
    assertEquals(1, firing.executedActions().size());
  }

  @Test
  void ruleFiringActionsAreImmutable() {
    var actions = new java.util.ArrayList<RuleAction>();
    actions.add(new RuleAction.DepositSignal("a", 1.0, null));
    var firing = new RuleFiring("rule-1", actions, Instant.now());
    assertThrows(
        UnsupportedOperationException.class,
        () -> firing.executedActions().add(new RuleAction.DepositSignal("b", 1.0, null)));
  }

  @Test
  void ruleRegistrationConstruction() {
    var rule = new LocalRule("r1", new RuleCondition.PredicateCondition(ctx -> true), List.of(), 0);
    var reg = new RuleRegistration("r1", rule, Instant.now());
    assertEquals("r1", reg.ruleId());
  }

  @Test
  void ruleConfigDefaults() {
    var config = RuleConfig.defaults();
    assertEquals(50, config.maxRulesPerCase());
    assertEquals(100, config.maxActionsPerCycle());
    assertEquals(100, config.ruleEvaluationTimeoutMs());
  }
}
