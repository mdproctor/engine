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
package io.casehub.engine.planning.decomposition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionContext;
import io.casehub.engine.plan.DecompositionMethod;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.TaskNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExplicitHtnDecompositionStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ExplicitHtnDecompositionStrategy strategy = new ExplicitHtnDecompositionStrategy();

  private DecompositionContext<JsonNode> contextWithState(ObjectNode state) {
    return new DecompositionContext<>() {
      @Override
      public JsonNode state() {
        return state;
      }

      @Override
      public int depth() {
        return 0;
      }
    };
  }

  private GoalStep leaf(String name, String capability) {
    return new GoalStep(UUID.randomUUID(), name, capability, Instant.now());
  }

  private DecompositionMethod<JsonNode> unconditionalMethod(GoalStep... leaves) {
    DecompositionStrategy<JsonNode> inlineStrategy =
        (task, ctx) -> DagPlan.sequence(List.of(leaves));
    return new DecompositionMethod<>(state -> true, inlineStrategy);
  }

  private DecompositionMethod<JsonNode> guardedMethod(
      String jqField, String jqValue, GoalStep... leaves) {
    DecompositionStrategy<JsonNode> inlineStrategy =
        (task, ctx) -> DagPlan.sequence(List.of(leaves));
    return new DecompositionMethod<>(
        state -> state.has(jqField) && jqValue.equals(state.get(jqField).asText()),
        inlineStrategy,
        jqField + " == " + jqValue);
  }

  @Test
  void singleMethod_noGuard_producesSequentialPlan() {
    var leaf1 = leaf("triage", "triage-assessment");
    var leaf2 = leaf("resolve", "resolution");
    var root =
        new TaskNode.CompoundTask<JsonNode>("incident", List.of(unconditionalMethod(leaf1, leaf2)));

    var context = contextWithState(MAPPER.createObjectNode());
    var plan = strategy.decompose(root, context);

    assertThat(plan.nodes()).hasSize(2);
  }

  @Test
  void guardedMethods_firstMatchSelected() {
    var highLeaf = leaf("escalate", "escalation");
    var lowLeaf = leaf("auto-resolve", "auto-resolution");

    var highMethod = guardedMethod("severity", "high", highLeaf);
    var lowMethod = guardedMethod("severity", "low", lowLeaf);
    var root = new TaskNode.CompoundTask<JsonNode>("incident", List.of(highMethod, lowMethod));

    ObjectNode state = MAPPER.createObjectNode().put("severity", "high");
    var plan = strategy.decompose(root, contextWithState(state));

    assertThat(plan.nodes()).hasSize(1);
    assertThat(plan.nodes().values().iterator().next().task().id()).isEqualTo(highLeaf.id());
  }

  @Test
  void guardedMethods_secondMatchWhenFirstFails() {
    var highLeaf = leaf("escalate", "escalation");
    var lowLeaf = leaf("auto-resolve", "auto-resolution");

    var highMethod = guardedMethod("severity", "high", highLeaf);
    var lowMethod = guardedMethod("severity", "low", lowLeaf);
    var root = new TaskNode.CompoundTask<JsonNode>("incident", List.of(highMethod, lowMethod));

    ObjectNode state = MAPPER.createObjectNode().put("severity", "low");
    var plan = strategy.decompose(root, contextWithState(state));

    assertThat(plan.nodes()).hasSize(1);
    assertThat(plan.nodes().values().iterator().next().task().id()).isEqualTo(lowLeaf.id());
  }

  @Test
  void noMatchingMethod_throws() {
    var method = guardedMethod("severity", "critical", leaf("x", "y"));
    var root = new TaskNode.CompoundTask<JsonNode>("incident", List.of(method));

    ObjectNode state = MAPPER.createObjectNode().put("severity", "low");
    assertThatThrownBy(() -> strategy.decompose(root, contextWithState(state)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No matching method");
  }

  @Test
  void strategyId_isExplicit() {
    assertThat(strategy.id()).isEqualTo("explicit");
  }
}
