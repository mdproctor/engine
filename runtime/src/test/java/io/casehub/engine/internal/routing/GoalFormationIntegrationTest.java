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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(GoalFormationIntegrationTest.FormationProfile.class)
class GoalFormationIntegrationTest {

  private static final String TENANT = TenancyConstants.DEFAULT_TENANT_ID;

  @Inject GoalFormationCaseHub caseHub;
  @Inject GoalRevisionIntegrationTest.TestAgentRegistry agentRegistry;
  @Inject GoalFormationEvaluator goalFormationEvaluator;
  @Inject CaseInstanceRepository caseInstanceRepository;

  @BeforeEach
  void setUp() {
    agentRegistry.reset();
  }

  @Test
  void formsNewGoalAndRegisters() {
    agentRegistry.seed(
        AgentDescriptor.builder()
            .agentId("agent-form-1")
            .name("analysis-agent")
            .slot("default")
            .tenancyId(TENANT)
            .goals(
                List.of(
                    new AgentGoal(
                        "find-bugs",
                        "Find bugs in code",
                        GoalPriority.PRIMARY,
                        Visibility.PUBLIC,
                        List.of())))
            .build());

    var caseId = caseHub.startCase(Map.of("task", "analysis-1"));
    CaseInstance caseInstance = caseInstanceRepository.findByUuid(caseId, TENANT);

    goalFormationEvaluator.evaluate(
        "analysis-worker",
        caseInstance,
        List.of(
            "Agent consistently improves code quality across reviews",
            "Pattern of catching subtle performance issues"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(agentRegistry.registrations()).isNotEmpty();
              AgentDescriptor updated =
                  agentRegistry.registrations().get(agentRegistry.registrations().size() - 1);
              assertThat(updated.goals()).hasSize(2);
              AgentGoal newGoal =
                  updated.goals().stream()
                      .filter(g -> g.name().equals("quality-metrics"))
                      .findFirst()
                      .orElseThrow();
              assertThat(newGoal.priority()).isEqualTo(GoalPriority.SECONDARY);
              assertThat(newGoal.description()).isEqualTo("Track code quality metrics over time");
              assertThat(newGoal.visibility()).isEqualTo(Visibility.PUBLIC);
              assertThat(newGoal.capabilities()).isEmpty();
            });
  }

  @Test
  void skipsWhenInsightsEmpty() {
    agentRegistry.seed(
        AgentDescriptor.builder()
            .agentId("agent-form-1")
            .name("analysis-agent")
            .slot("default")
            .tenancyId(TENANT)
            .goals(
                List.of(
                    new AgentGoal(
                        "find-bugs",
                        "Find bugs in code",
                        GoalPriority.PRIMARY,
                        Visibility.PUBLIC,
                        List.of())))
            .build());

    var caseId = caseHub.startCase(Map.of("task", "empty-1"));
    CaseInstance caseInstance = caseInstanceRepository.findByUuid(caseId, TENANT);

    goalFormationEvaluator.evaluate("analysis-worker", caseInstance, List.of());

    await()
        .pollDelay(1, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(agentRegistry.registrations()).isEmpty());
  }

  @Test
  void respectsCapacityLimit() {
    var goalsAtCapacity = new java.util.ArrayList<AgentGoal>();
    for (int i = 0; i < 10; i++) {
      goalsAtCapacity.add(
          new AgentGoal(
              "goal-" + i, "Goal " + i, GoalPriority.SECONDARY, Visibility.PUBLIC, List.of()));
    }

    agentRegistry.seed(
        AgentDescriptor.builder()
            .agentId("agent-form-1")
            .name("analysis-agent")
            .slot("default")
            .tenancyId(TENANT)
            .goals(goalsAtCapacity)
            .build());

    var caseId = caseHub.startCase(Map.of("task", "capacity-1"));
    CaseInstance caseInstance = caseInstanceRepository.findByUuid(caseId, TENANT);

    goalFormationEvaluator.evaluate("analysis-worker", caseInstance, List.of("Some insight"));

    await()
        .pollDelay(2, TimeUnit.SECONDS)
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              boolean hasCapacityAgent =
                  agentRegistry.registrations().stream().anyMatch(d -> d.goals().size() > 10);
              assertThat(hasCapacityAgent).isFalse();
            });
  }

  public static class FormationProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.ofEntries(
          Map.entry("casehub.engine.goal.formation.enabled", "true"),
          Map.entry("casehub.engine.goal.formation.auto-approve", "true"),
          Map.entry("casehub.engine.goal.formation.cooldown-minutes", "0"),
          Map.entry("casehub.engine.goal.formation.max-new-per-reflection", "2"),
          Map.entry("casehub.engine.goal.revision.enabled", "false"),
          Map.entry("casehub.engine.goal.formation.strategy", "llm"));
    }
  }

  @jakarta.annotation.Priority(1)
  @jakarta.enterprise.inject.Alternative
  @ApplicationScoped
  public static class TestChatModelProvider implements ChatModelProvider {
    private static final String RESPONSE_JSON =
        "{\"goals\": [{\"name\": \"quality-metrics\", "
            + "\"description\": \"Track code quality metrics over time\", "
            + "\"suggestedPriority\": \"SECONDARY\", "
            + "\"formationReason\": \"Consistent quality improvement pattern observed\"}], "
            + "\"rationale\": \"Reflection insights suggest quality awareness\"}";

    @Override
    public ModelType type() {
      return ModelType.OPENAI;
    }

    @Override
    public dev.langchain4j.model.chat.ChatModel get() {
      return new dev.langchain4j.model.chat.ChatModel() {
        @Override
        public dev.langchain4j.model.chat.response.ChatResponse chat(
            dev.langchain4j.model.chat.request.ChatRequest request) {
          return dev.langchain4j.model.chat.response.ChatResponse.builder()
              .aiMessage(dev.langchain4j.data.message.AiMessage.from(RESPONSE_JSON))
              .build();
        }
      };
    }
  }

  @ApplicationScoped
  public static class GoalFormationCaseHub extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("code-analysis")
            .inputSchema("{ task: .task }")
            .outputSchema(".")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-goal-formation")
          .name("Goal Formation Test")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("analysis-worker")
                  .capabilityName("code-analysis")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("result", "analysed"))))
                  .executionPolicy(new ExecutionPolicy(5000, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".task != null"))
                  .build())
          .agentDescriptor(
              "analysis-worker",
              AgentDescriptor.builder()
                  .agentId("agent-form-1")
                  .name("analysis-agent")
                  .slot("default")
                  .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
                  .goals(
                      List.of(
                          new AgentGoal(
                              "find-bugs",
                              "Find bugs in code",
                              GoalPriority.PRIMARY,
                              Visibility.PUBLIC,
                              List.of())))
                  .build())
          .build();
    }
  }
}
