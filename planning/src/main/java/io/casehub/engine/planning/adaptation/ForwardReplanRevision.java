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
package io.casehub.engine.planning.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.engine.plan.adaptation.CompletedStep;
import io.casehub.engine.plan.adaptation.PlanRevisionStrategy;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ForwardReplanRevision implements PlanRevisionStrategy {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String SYSTEM_PROMPT =
      "You are a planning assistant. A plan is in progress. Some steps have "
          + "completed. Given the current state and remaining capabilities, produce an "
          + "updated plan for the remaining work. Each step must reference exactly one "
          + "capability by name from the provided list. "
          + "Return a JSON object with a 'steps' array. Each step has: "
          + "'id' (unique string), 'description' (what this step does), "
          + "'capabilityName' (must match one of the available capabilities), "
          + "and optional 'dependsOn' (array of step ids this step depends on). "
          + "Produce a sequential plan where each step depends on the previous.";

  @Inject Instance<ChatModelProvider> chatModelProviders;

  @Override
  public String id() {
    return "forward-replan";
  }

  @Override
  public RevisedPlan revise(RevisionContext context) {
    if (chatModelProviders.isUnsatisfied()) {
      throw new UnsupportedOperationException("No ChatModelProvider available for plan revision");
    }

    {
      var adaptCtx = context.adaptationContext();
      var completedHistory = buildCompletedHistory(adaptCtx.completedSteps());
      var capList = buildCapabilityList(context);
      var contextStr = truncateContext(adaptCtx.currentContext());

      var userPrompt =
          "Goal: "
              + adaptCtx.goalName()
              + "\n\n"
              + completedHistory
              + "\n\n"
              + capList
              + "\n\nCurrent context:\n"
              + contextStr
              + "\n\nProduce the remaining steps as a JSON 'steps' array.";

      var planningConstraints = adaptCtx.definition().getPlanningConstraints();
      if (planningConstraints != null) {
        var constraintText = buildConstraintText(planningConstraints);
        if (!constraintText.isEmpty()) {
          userPrompt = userPrompt + "\n\n" + constraintText;
        }
      }

      var agent =
          Agent.builder().systemPrompt(SYSTEM_PROMPT).model(chatModelProviders.get().get()).build();

      var result = agent.execute(Map.of("prompt", userPrompt));
      var output = result.output();

      JsonNode responseJson;
      try {
        var outputStr =
            output instanceof Map ? MAPPER.writeValueAsString(output) : output.toString();
        responseJson = MAPPER.readTree(outputStr);
      } catch (Exception e) {
        throw new AgentException("Failed to parse plan revision response", e);
      }

      var stepsNode = responseJson.get("steps");
      if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
        throw new AgentException(
            "Plan revision returned no steps for goal: " + adaptCtx.goalName());
      }

      var steps = new ArrayList<PlanStepDescriptor>();
      for (var stepNode : stepsNode) {
        steps.add(
            new PlanStepDescriptor(
                stepNode.has("id")
                    ? stepNode.get("id").asText()
                    : java.util.UUID.randomUUID().toString(),
                stepNode.get("description").asText(),
                stepNode.get("capabilityName").asText()));
      }

      var rationale = responseJson.has("rationale") ? responseJson.get("rationale").asText() : null;

      return new RevisedPlan(steps, rationale);
    }
  }

  private String buildCompletedHistory(List<CompletedStep> steps) {
    if (steps.isEmpty()) return "No steps completed yet.";
    var sb = new StringBuilder("Completed steps:\n");
    for (int i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      sb.append("  ").append(i + 1).append(". ").append(step.description());
      if (!step.output().isEmpty()) {
        sb.append(" -> Output: ").append(step.output());
      }
      sb.append("\n");
    }
    return sb.toString();
  }

  private String buildCapabilityList(RevisionContext context) {
    return context.capabilities().stream()
        .map(c -> c.name() + (c.description() != null ? " - " + c.description() : ""))
        .collect(Collectors.joining("\n  - ", "Available capabilities:\n  - ", ""));
  }

  private String truncateContext(JsonNode context) {
    if (context == null) return "{}";
    var str = context.toString();
    return str.length() > 2000 ? str.substring(0, 2000) + "..." : str;
  }

  private String buildConstraintText(io.casehub.engine.plan.PlanningConstraints constraints) {
    if (constraints.timeBudget() == null
        && constraints.resourceLimit() == null
        && constraints.costBudgets().isEmpty()
        && constraints.weights().isEmpty()) {
      return "";
    }
    var sb = new StringBuilder("Constraints:\n");
    if (constraints.timeBudget() != null) {
      long minutes = constraints.timeBudget().toMinutes();
      sb.append("- Time budget: ").append(minutes).append(" minutes. ");
      sb.append("Plan steps that can complete within this time.\n");
    }
    if (constraints.resourceLimit() != null) {
      sb.append("- Resource limit: ").append(constraints.resourceLimit());
      sb.append(" available agents. Prefer parallelism when resource limits allow.\n");
    }
    for (var entry : constraints.costBudgets().entrySet()) {
      var key = entry.getKey();
      var label = Character.toUpperCase(key.charAt(0)) + key.substring(1);
      sb.append("- ").append(label).append(" budget: ").append(entry.getValue());
      sb.append(". Plan steps that stay within this ").append(key).append(" budget.\n");
    }
    if (!constraints.weights().isEmpty()) {
      sb.append("- Priority weights: ");
      var entries =
          constraints.weights().entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(java.util.stream.Collectors.joining(", "));
      sb.append(entries);
      sb.append(". Prioritize steps aligned with higher-weighted dimensions. ");
      sb.append("If constraints force trade-offs, keep steps serving high-weight priorities.\n");
    }
    return sb.toString();
  }
}
