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
import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RepairStrategy;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class LlmRepairStrategy implements RepairStrategy {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String SYSTEM_PROMPT =
      "You are a plan repair assistant. A specific step in a plan has failed. "
          + "Your task is to repair the plan by replacing or modifying ONLY the failed step. "
          + "Minimise changes to the rest of the plan. Each step must reference exactly one "
          + "capability by name from the provided list. "
          + "Return a JSON object with a 'steps' array. Each step has: "
          + "'id' (unique string), 'description' (what this step does), "
          + "'capabilityName' (must match one of the available capabilities).";

  private final Instance<ChatModelProvider> chatModelProviders;

  @Inject
  public LlmRepairStrategy(Instance<ChatModelProvider> chatModelProviders) {
    this.chatModelProviders = chatModelProviders;
  }

  @Override
  public String id() {
    return "llm-repair";
  }

  @Override
  public RevisedPlan revise(RevisionContext context) {
    if (chatModelProviders.isUnsatisfied()) {
      throw new UnsupportedOperationException("No ChatModelProvider available for LLM repair");
    }

    var adaptCtx = context.adaptationContext();

    String failedStep = "";
    String failureReason = "";
    if (context.cause() instanceof AdaptationCause.StepFailed failed) {
      failedStep = failed.stepId();
      failureReason = failed.reason();
    }

    String critique = extractCritique(adaptCtx.currentContext(), failedStep);

    String capList =
        context.capabilities().stream()
            .map(c -> c.name() + (c.description() != null ? " - " + c.description() : ""))
            .collect(Collectors.joining("\n  - ", "Available capabilities:\n  - ", ""));

    var userPrompt =
        "Failed step: " + failedStep + "\n" + "Failure reason: " + failureReason + "\n";
    if (critique != null) {
      userPrompt += "Failure analysis: " + critique + "\n";
    }
    userPrompt +=
        "\n"
            + capList
            + "\n\n"
            + "Produce replacement step(s) as a JSON 'steps' array. "
            + "Focus on repairing the failed step only.";

    var agent =
        Agent.builder().systemPrompt(SYSTEM_PROMPT).model(chatModelProviders.get().get()).build();

    var result = agent.execute(Map.of("prompt", userPrompt));
    var output = result.output();

    JsonNode responseJson;
    try {
      var outputStr = output instanceof Map ? MAPPER.writeValueAsString(output) : output.toString();
      responseJson = MAPPER.readTree(outputStr);
    } catch (Exception e) {
      throw new AgentException("Failed to parse LLM repair response", e);
    }

    var stepsNode = responseJson.get("steps");
    if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
      throw new AgentException("LLM repair returned no steps for failed step: " + failedStep);
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
    return new RevisedPlan(
        steps, rationale != null ? rationale : "LLM repair for step: " + failedStep);
  }

  static String extractCritique(JsonNode context, String bindingName) {
    if (context == null || !context.has("_diagnostics")) return null;
    JsonNode diagnostics = context.get("_diagnostics");
    if (!diagnostics.has(bindingName)) return null;
    JsonNode bindingDiag = diagnostics.get(bindingName);
    return bindingDiag.has("critique") ? bindingDiag.get("critique").asText() : null;
  }
}
