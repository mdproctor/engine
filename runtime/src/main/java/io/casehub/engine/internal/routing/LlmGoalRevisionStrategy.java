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
package io.casehub.engine.internal.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.spi.routing.GoalRevisionAction;
import io.casehub.api.spi.routing.GoalRevisionContext;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.api.spi.routing.GoalRevisionStrategy;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class LlmGoalRevisionStrategy implements GoalRevisionStrategy {

  private static final Logger LOG = Logger.getLogger(LlmGoalRevisionStrategy.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Instance<ChatModelProvider> chatModelProviders;

  @Inject
  public LlmGoalRevisionStrategy(Instance<ChatModelProvider> chatModelProviders) {
    this.chatModelProviders = chatModelProviders;
  }

  @Override
  public String id() {
    return "llm";
  }

  @Override
  public GoalRevisionProposal revise(GoalRevisionContext context) {
    if (chatModelProviders.isUnsatisfied()) {
      throw new UnsupportedOperationException("No ChatModelProvider available for goal revision");
    }

    String userPrompt = buildPrompt(context);
    Agent agent =
        Agent.builder()
            .systemPrompt(
                "You are a goal effectiveness analyst. Given an agent's goals and their "
                    + "performance metrics, evaluate each goal and recommend one of three actions:\n"
                    + "- REVISE: refine the goal description to better capture what the agent "
                    + "accomplishes. Only when meaningfully misaligned with observed outcomes.\n"
                    + "- ABANDON: drop the goal entirely. Only when the goal is unachievable or "
                    + "no longer relevant based on persistent failure patterns.\n"
                    + "- COMPLETE: mark the goal as achieved. Only when the goal has been "
                    + "consistently met and keeping it adds no further value.\n"
                    + "Respond with JSON only.")
            .model(chatModelProviders.get().get())
            .build();

    WorkerResult result = agent.execute(Map.of("prompt", userPrompt));
    Object output = result.output();

    String outputStr;
    try {
      outputStr = output instanceof Map ? MAPPER.writeValueAsString(output) : output.toString();
    } catch (Exception e) {
      throw new AgentException("Failed to serialize LLM response", e);
    }

    return parseResponse(outputStr);
  }

  private String buildPrompt(GoalRevisionContext context) {
    StringBuilder sb = new StringBuilder();
    sb.append("Agent: ").append(context.agentId()).append("\n\nGoals:\n");

    for (AgentGoal goal : context.goals()) {
      GoalOutcomeCounts counts = context.counts().get(goal.name());
      sb.append("- ").append(goal.name()).append(": ").append(goal.description());
      sb.append(" (priority: ").append(goal.priority());
      if (counts != null) {
        sb.append(
            String.format(
                ", success: %d, failure: %d, rate: %.0f%%",
                counts.successCount(), counts.failureCount(), counts.successRate() * 100));
      }
      sb.append(")\n");
    }

    sb.append("\nRespond with JSON: {\"revisions\": [{\"goalName\": \"...\", ")
        .append("\"action\": \"REVISE|ABANDON|COMPLETE\", ")
        .append("\"revisedDescription\": \"...\"|null, \"revisionReason\": \"...\"}], ")
        .append("\"rationale\": \"...\"}");
    return sb.toString();
  }

  private GoalRevisionProposal parseResponse(String response) {
    JsonNode root;
    try {
      root = MAPPER.readTree(response);
    } catch (Exception e) {
      throw new AgentException("Failed to parse LLM goal revision response", e);
    }
    JsonNode revisionsNode = root.get("revisions");
    String rationale = root.has("rationale") ? root.get("rationale").asText() : "";

    List<GoalRevisionProposal.RevisedGoal> revisions = new ArrayList<>();
    if (revisionsNode != null && revisionsNode.isArray()) {
      for (JsonNode node : revisionsNode) {
        try {
          String goalName = node.get("goalName").asText();
          GoalRevisionAction action = parseAction(node);
          String desc =
              node.has("revisedDescription") && !node.get("revisedDescription").isNull()
                  ? node.get("revisedDescription").asText()
                  : null;
          if (action == GoalRevisionAction.REVISE && desc == null) {
            LOG.debugf(
                "Skipping revision for goal %s: action defaulted to REVISE "
                    + "but description is null",
                goalName);
            continue;
          }
          String reason = node.get("revisionReason").asText();
          revisions.add(new GoalRevisionProposal.RevisedGoal(goalName, action, desc, reason));
        } catch (Exception e) {
          LOG.debugf("Skipping malformed revision entry: %s", e.getMessage());
        }
      }
    }
    return new GoalRevisionProposal(revisions, rationale);
  }

  private GoalRevisionAction parseAction(JsonNode node) {
    if (!node.has("action") || node.get("action").isNull()) {
      return GoalRevisionAction.REVISE;
    }
    try {
      return GoalRevisionAction.valueOf(node.get("action").asText().toUpperCase());
    } catch (IllegalArgumentException e) {
      return GoalRevisionAction.REVISE;
    }
  }
}
