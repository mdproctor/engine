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
                    + "performance metrics, evaluate whether any goal descriptions should be refined to "
                    + "better capture what the agent accomplishes. Only propose changes when a description "
                    + "is meaningfully misaligned with observed outcomes. Respond with JSON only.")
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
        .append("\"revisedDescription\": \"...\"|null, \"revisionReason\": \"...\"}], ")
        .append("\"rationale\": \"...\"}");
    return sb.toString();
  }

  private GoalRevisionProposal parseResponse(String response) {
    try {
      JsonNode root = MAPPER.readTree(response);
      JsonNode revisionsNode = root.get("revisions");
      String rationale = root.has("rationale") ? root.get("rationale").asText() : "";

      List<GoalRevisionProposal.RevisedGoal> revisions = new ArrayList<>();
      if (revisionsNode != null && revisionsNode.isArray()) {
        for (JsonNode node : revisionsNode) {
          String goalName = node.get("goalName").asText();
          String desc =
              node.has("revisedDescription") && !node.get("revisedDescription").isNull()
                  ? node.get("revisedDescription").asText()
                  : null;
          String reason = node.get("revisionReason").asText();
          revisions.add(new GoalRevisionProposal.RevisedGoal(goalName, desc, reason));
        }
      }
      return new GoalRevisionProposal(revisions, rationale);
    } catch (Exception e) {
      throw new AgentException("Failed to parse LLM goal revision response", e);
    }
  }
}
