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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.spi.observation.LocalRule;
import io.casehub.api.spi.observation.RuleAction;
import io.casehub.api.spi.observation.RuleCondition;
import io.casehub.api.spi.observation.RuleConfig;
import io.casehub.api.spi.observation.RuleFiring;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultRuleSpaceTest {

  private RuleRegistry registry;
  private DefaultRuleSpace ruleSpace;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new RuleRegistry();
    caseId = UUID.randomUUID();
    ruleSpace =
        new DefaultRuleSpace(registry, caseId, "agent-1", "binding-a", RuleConfig.defaults());
  }

  private LocalRule rule(String id) {
    return new LocalRule(
        id,
        new RuleCondition.PredicateCondition(ctx -> true),
        List.of(new RuleAction.DepositSignal("s", 1.0, null)),
        0);
  }

  @Test
  void registerAndMine() {
    var reg = ruleSpace.register(rule("r1"));
    assertNotNull(reg);
    assertEquals("r1", reg.ruleId());
    var mine = ruleSpace.mine();
    assertEquals(1, mine.size());
  }

  @Test
  void deregister() {
    ruleSpace.register(rule("r1"));
    ruleSpace.deregister("r1");
    assertTrue(ruleSpace.mine().isEmpty());
  }

  @Test
  void lastFiredInitiallyEmpty() {
    assertTrue(ruleSpace.lastFired().isEmpty());
  }

  @Test
  void lastFiredReturnsFiringsForThisAgent() {
    registry.storeFirings(
        caseId, "agent-1", List.of(new RuleFiring("r1", List.of(), java.time.Instant.now())));
    assertEquals(1, ruleSpace.lastFired().size());
  }

  @Test
  void lastFiredDoesNotReturnOtherAgentFirings() {
    registry.storeFirings(
        caseId, "agent-2", List.of(new RuleFiring("r1", List.of(), java.time.Instant.now())));
    assertTrue(ruleSpace.lastFired().isEmpty());
  }

  @Test
  void respectsMaxRulesPerCase() {
    var smallConfig = new RuleConfig(2, 100, 100);
    var space = new DefaultRuleSpace(registry, caseId, "agent-1", "binding-a", smallConfig);
    space.register(rule("r1"));
    space.register(rule("r2"));
    var reg = space.register(rule("r3"));
    assertNull(reg);
  }
}
