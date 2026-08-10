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
import io.casehub.api.model.RetrievedMemory;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class LlmGoalFormationStrategy implements GoalFormationStrategy {

  private static final Logger LOG = Logger.getLogger(LlmGoalFormationStrategy.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Instance<ChatModelProvider> chatModelProviders;

  @Inject
  public LlmGoalFormationStrategy(Instance<ChatModelProvider> chatModelProviders) {
    this.chatModelProviders = chatModelProviders;
  }

  @Override
  public String id() {
    return "llm";
  }

  @Override
  public Uni<GoalFormationProposal> propose(GoalFormationContext context) {
    if (chatModelProviders.isUnsatisfied()) {
      return Uni.createFrom()
          .failure(
              new UnsupportedOperationException(
                  "No ChatModelProvider available for goal formation"));
    }

    return Uni.createFrom()
        .item(
            () -> {
              String userPrompt = buildPrompt(context);
              Agent agent =
                  Agent.builder()
                      .systemPrompt(
                          "You are a goal discovery analyst. Given an agent's recent reflection "
                              + "insights, its current goals, and relevant memories, identify new goals "
                              + "the agent should pursue. Only propose goals that represent genuinely new "
                              + "objectives — not refinements of existing goals. Each goal must be "
                              + "specific, actionable, and distinct from existing goals. "
                              + "Respond with JSON only.")
                      .model(chatModelProviders.get().get())
                      .build();

              WorkerResult result = agent.execute(Map.of("prompt", userPrompt));
              Object output = result.output();

              String outputStr;
              try {
                outputStr =
                    output instanceof Map ? MAPPER.writeValueAsString(output) : output.toString();
              } catch (Exception e) {
                throw new AgentException("Failed to serialize LLM response", e);
              }

              return parseResponse(outputStr);
            });
  }

  private String buildPrompt(GoalFormationContext context) {
    StringBuilder sb = new StringBuilder();
    sb.append("Agent: ").append(context.agentId()).append("\n");
    sb.append("Remaining goal capacity: ").append(context.remainingCapacity()).append("\n");

    sb.append("\nCurrent goals:\n");
    for (AgentGoal goal : context.existingGoals()) {
      sb.append("- ")
          .append(goal.name())
          .append(": ")
          .append(goal.description())
          .append(" (priority: ")
          .append(goal.priority())
          .append(")\n");
    }

    sb.append("\nRecent reflection insights:\n");
    for (String insight : context.reflectionInsights()) {
      sb.append("- ").append(insight).append("\n");
    }

    if (!context.recentMemories().isEmpty()) {
      sb.append("\nRelevant memories:\n");
      for (RetrievedMemory memory : context.recentMemories()) {
        sb.append("- ").append(memory.text()).append("\n");
      }
    }

    sb.append("\nRespond with JSON: {\"goals\": [{\"name\": \"...\", ")
        .append("\"description\": \"...\", \"suggestedPriority\": \"SECONDARY\"|null, ")
        .append("\"formationReason\": \"...\"}], \"rationale\": \"...\"}");
    return sb.toString();
  }

  private GoalFormationProposal parseResponse(String response) {
    try {
      JsonNode root = MAPPER.readTree(response);
      JsonNode goalsNode = root.get("goals");
      String rationale = root.has("rationale") ? root.get("rationale").asText() : "";

      List<GoalFormationProposal.ProposedGoal> goals = new ArrayList<>();
      if (goalsNode != null && goalsNode.isArray()) {
        for (JsonNode node : goalsNode) {
          String name = node.get("name").asText();
          String description = node.get("description").asText();
          GoalPriority priority = null;
          if (node.has("suggestedPriority") && !node.get("suggestedPriority").isNull()) {
            try {
              priority = GoalPriority.valueOf(node.get("suggestedPriority").asText());
            } catch (IllegalArgumentException e) {
              priority = GoalPriority.SECONDARY;
            }
          }
          String reason = node.get("formationReason").asText();
          goals.add(new GoalFormationProposal.ProposedGoal(name, description, priority, reason));
        }
      }
      return new GoalFormationProposal(goals, rationale);
    } catch (Exception e) {
      throw new AgentException("Failed to parse LLM goal formation response", e);
    }
  }
}
