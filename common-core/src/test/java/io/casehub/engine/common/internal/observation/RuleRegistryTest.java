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
package io.casehub.engine.common.internal.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.spi.observation.LocalRule;
import io.casehub.api.spi.observation.RuleAction;
import io.casehub.api.spi.observation.RuleCondition;
import io.casehub.api.spi.observation.RuleFiring;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleRegistryTest {

  private RuleRegistry registry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new RuleRegistry();
    caseId = UUID.randomUUID();
  }

  private LocalRule rule(String id, int priority) {
    return new LocalRule(
        id,
        new RuleCondition.PredicateCondition(ctx -> true),
        List.of(new RuleAction.DepositSignal("s", 1.0, null)),
        priority);
  }

  @Test
  void registerAndRetrieve() {
    String ruleId = registry.registerRule(caseId, "agent-1", "binding-a", rule("r1", 0), 50);
    assertNotNull(ruleId);
    var rules = registry.getRulesForAgent(caseId, "agent-1");
    assertEquals(1, rules.size());
    assertEquals("r1", rules.get(0).id());
  }

  @Test
  void respectsMaxPerCase() {
    for (int i = 0; i < 3; i++) {
      registry.registerRule(caseId, "agent-1", "binding-a", rule("r" + i, 0), 3);
    }
    String overflow =
        registry.registerRule(caseId, "agent-1", "binding-a", rule("r-overflow", 0), 3);
    assertNull(overflow);
    assertEquals(3, registry.ruleCount(caseId));
  }

  @Test
  void deduplicatesByRuleId() {
    registry.registerRule(caseId, "agent-1", "binding-a", rule("r1", 5), 50);
    registry.registerRule(caseId, "agent-1", "binding-a", rule("r1", 10), 50);
    var rules = registry.getRulesForAgent(caseId, "agent-1");
    assertEquals(1, rules.size());
    assertEquals(10, rules.get(0).priority());
  }

  @Test
  void deregisterByRuleId() {
    registry.registerRule(caseId, "agent-1", "binding-a", rule("r1", 0), 50);
    registry.deregisterRule(caseId, "r1");
    assertTrue(registry.getRulesForAgent(caseId, "agent-1").isEmpty());
  }

  @Test
  void getRulesForCaseGroupsByAgent() {
    registry.registerRule(caseId, "agent-1", "b", rule("r1", 0), 50);
    registry.registerRule(caseId, "agent-2", "b", rule("r2", 0), 50);
    var byAgent = registry.getRulesForCase(caseId);
    assertEquals(2, byAgent.size());
    assertTrue(byAgent.containsKey("agent-1"));
    assertTrue(byAgent.containsKey("agent-2"));
  }

  @Test
  void storeFiringsAndRetrieve() {
    var firings =
        List.of(
            new RuleFiring(
                "r1", List.of(new RuleAction.DepositSignal("x", 0.5, null)), Instant.now()));
    registry.storeFirings(caseId, "agent-1", firings);
    var retrieved = registry.getFirings(caseId, "agent-1");
    assertEquals(1, retrieved.size());
    assertEquals("r1", retrieved.get(0).ruleId());
  }

  @Test
  void storeFiringsReplacesPerCycle() {
    registry.storeFirings(
        caseId, "agent-1", List.of(new RuleFiring("r1", List.of(), Instant.now())));
    registry.storeFirings(
        caseId, "agent-1", List.of(new RuleFiring("r2", List.of(), Instant.now())));
    var retrieved = registry.getFirings(caseId, "agent-1");
    assertEquals(1, retrieved.size());
    assertEquals("r2", retrieved.get(0).ruleId());
  }

  @Test
  void unregisterByAgent() {
    registry.registerRule(caseId, "agent-1", "b", rule("r1", 0), 50);
    registry.registerRule(caseId, "agent-2", "b", rule("r2", 0), 50);
    registry.unregisterByAgent(caseId, "agent-1");
    assertTrue(registry.getRulesForAgent(caseId, "agent-1").isEmpty());
    assertEquals(1, registry.getRulesForAgent(caseId, "agent-2").size());
  }

  @Test
  void unregisterByBinding() {
    registry.registerRule(caseId, "agent-1", "binding-a", rule("r1", 0), 50);
    registry.registerRule(caseId, "agent-1", "binding-b", rule("r2", 0), 50);
    registry.unregisterByBinding(caseId, Set.of("binding-a"));
    var rules = registry.getRulesForAgent(caseId, "agent-1");
    assertEquals(1, rules.size());
    assertEquals("r2", rules.get(0).id());
  }

  @Test
  void evictByCase() {
    registry.registerRule(caseId, "agent-1", "b", rule("r1", 0), 50);
    registry.storeFirings(
        caseId, "agent-1", List.of(new RuleFiring("r1", List.of(), Instant.now())));
    registry.evictByCase(caseId);
    assertTrue(registry.getRulesForAgent(caseId, "agent-1").isEmpty());
    assertTrue(registry.getFirings(caseId, "agent-1").isEmpty());
    assertEquals(0, registry.ruleCount(caseId));
  }

  @Test
  void resetClearsEverything() {
    registry.registerRule(caseId, "agent-1", "b", rule("r1", 0), 50);
    registry.reset();
    assertEquals(0, registry.ruleCount(caseId));
  }

  @Test
  void emptyReturnsForUnknownCase() {
    assertTrue(registry.getRulesForCase(UUID.randomUUID()).isEmpty());
    assertTrue(registry.getRulesForAgent(UUID.randomUUID(), "x").isEmpty());
    assertTrue(registry.getFirings(UUID.randomUUID(), "x").isEmpty());
    assertEquals(0, registry.ruleCount(UUID.randomUUID()));
  }
}
