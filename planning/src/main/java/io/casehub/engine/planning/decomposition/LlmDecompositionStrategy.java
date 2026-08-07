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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.engine.plan.DagNode;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionContext;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.JoinType;
import io.casehub.engine.plan.TaskNode;
import io.casehub.worker.api.Capability;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class LlmDecompositionStrategy implements DecompositionStrategy<JsonNode> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String SYSTEM_PROMPT =
      "You are a planning assistant. Given a goal and available capabilities, "
          + "produce an ordered plan of sub-steps. Each step must reference exactly "
          + "one capability by name from the provided list. "
          + "Return a JSON object with a 'steps' array. Each step has: "
          + "'id' (unique string), 'description' (what this step does), "
          + "'capabilityName' (must match one of the available capabilities), "
          + "and optional 'dependsOn' (array of step ids this step depends on). "
          + "Steps without dependsOn are entry points. "
          + "Produce a sequential plan where each step depends on the previous.";

  @Inject Instance<ChatModelProvider> chatModelProviders;

  @Override
  public String id() {
    return "llm";
  }

  @Override
  public Uni<DagPlan<TaskNode.LeafTask<JsonNode>>> decompose(
      TaskNode<JsonNode> task, DecompositionContext<JsonNode> context) {

    if (chatModelProviders.isUnsatisfied()) {
      return Uni.createFrom()
          .failure(
              new UnsupportedOperationException(
                  "No ChatModelProvider available for LLM decomposition"));
    }

    return Uni.createFrom()
        .item(
            () -> {
              var capabilities =
                  (context instanceof GoalDecompositionContext gdc)
                      ? gdc.availableCapabilities()
                      : List.<Capability>of();

              var goalName =
                  (task instanceof TaskNode.CompoundTask<JsonNode> ct) ? ct.name() : "unknown";

              var capList =
                  capabilities.stream()
                      .map(c -> c.name() + (c.description() != null ? " — " + c.description() : ""))
                      .collect(Collectors.joining("\n  - ", "Available capabilities:\n  - ", ""));

              var contextStr = context.state().toString();
              if (contextStr.length() > 2000) {
                contextStr = contextStr.substring(0, 2000) + "...";
              }

              var userPrompt =
                  "Goal: " + goalName + "\n\n" + capList + "\n\nContext:\n" + contextStr;

              var agent =
                  Agent.builder()
                      .systemPrompt(SYSTEM_PROMPT)
                      .model(chatModelProviders.get().get())
                      .build();

              var result = agent.execute(Map.of("prompt", userPrompt));
              var output = result.output();

              JsonNode responseJson;
              try {
                var outputStr =
                    output instanceof Map ? MAPPER.writeValueAsString(output) : output.toString();
                responseJson = MAPPER.readTree(outputStr);
              } catch (Exception e) {
                throw new AgentException("Failed to parse LLM decomposition response", e);
              }

              var stepsNode = responseJson.get("steps");
              if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
                throw new AgentException(
                    "LLM decomposition returned no steps for goal: " + goalName);
              }

              var nodes = new ArrayList<DagNode<TaskNode.LeafTask<JsonNode>>>();
              for (var stepNode : stepsNode) {
                var stepId =
                    stepNode.has("id") ? stepNode.get("id").asText() : UUID.randomUUID().toString();
                var desc = stepNode.get("description").asText();
                var capName = stepNode.get("capabilityName").asText();
                var dependsOn = new HashSet<String>();
                if (stepNode.has("dependsOn") && stepNode.get("dependsOn").isArray()) {
                  for (var dep : stepNode.get("dependsOn")) {
                    dependsOn.add(dep.asText());
                  }
                }

                var goalStep = new GoalStep(UUID.randomUUID(), desc, capName, Instant.now());
                nodes.add(new DagNode<>(stepId, goalStep, dependsOn, JoinType.ALL_OF));
              }

              return DagPlan.fromNodes(nodes);
            });
  }
}
