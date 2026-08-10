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
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.DefaultGoalEvolution;
import io.casehub.eidos.api.GoalEvolution;
import io.casehub.eidos.api.GoalOutcome;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.eidos.api.InMemoryGoalSignalStore;
import io.casehub.eidos.api.Visibility;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(GoalRevisionIntegrationTest.RevisionProfile.class)
class GoalRevisionIntegrationTest {

  private static final String TENANT = TenancyConstants.DEFAULT_TENANT_ID;

  @Inject GoalRevisionCaseHub caseHub;
  @Inject TestGoalSignalStore goalSignalStore;
  @Inject TestAgentRegistry agentRegistry;

  @BeforeEach
  void setUp() {
    agentRegistry.reset();
    goalSignalStore.clear("agent-rev-1", TENANT);
  }

  @Test
  void promotesSecondaryGoalAfterThreshold() {
    for (int i = 0; i < 9; i++) {
      goalSignalStore.recordOutcome("agent-rev-1", TENANT, "find-bugs", GoalOutcome.SUCCESS);
    }
    goalSignalStore.recordOutcome("agent-rev-1", TENANT, "find-bugs", GoalOutcome.FAILURE);

    agentRegistry.seed(
        AgentDescriptor.builder()
            .agentId("agent-rev-1")
            .name("review-agent")
            .slot("default")
            .tenancyId(TENANT)
            .goals(
                List.of(
                    new AgentGoal(
                        "find-bugs",
                        "Find bugs in code",
                        GoalPriority.SECONDARY,
                        Visibility.PUBLIC,
                        List.of())))
            .build());

    for (int i = 0; i < 3; i++) {
      caseHub.startCase(Map.of("task", "review-" + i));
    }

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(agentRegistry.registrations()).isNotEmpty();
              AgentDescriptor updated =
                  agentRegistry.registrations().get(agentRegistry.registrations().size() - 1);
              AgentGoal promotedGoal =
                  updated.goals().stream()
                      .filter(g -> g.name().equals("find-bugs"))
                      .findFirst()
                      .orElseThrow();
              assertThat(promotedGoal.priority()).isEqualTo(GoalPriority.PRIMARY);
            });

    assertThat(goalSignalStore.outcomeCounts("agent-rev-1", TENANT)).isEmpty();
  }

  @Test
  void dampenedDecaysSignalsWithoutRegistering() {
    agentRegistry.seed(
        AgentDescriptor.builder()
            .agentId("agent-rev-1")
            .name("review-agent")
            .slot("default")
            .tenancyId(TENANT)
            .goals(
                List.of(
                    new AgentGoal(
                        "find-bugs",
                        "Find bugs in code",
                        GoalPriority.SECONDARY,
                        Visibility.PUBLIC,
                        List.of())))
            .build());

    for (int i = 0; i < 3; i++) {
      caseHub.startCase(Map.of("task", "dampened-" + i));
    }

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              Map<String, GoalOutcomeCounts> counts =
                  goalSignalStore.outcomeCounts("agent-rev-1", TENANT);
              if (counts.containsKey("find-bugs")) {
                assertThat(counts.get("find-bugs").successCount()).isLessThan(3);
              }
            });

    assertThat(agentRegistry.registrations()).isEmpty();
  }

  public static class RevisionProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "casehub.engine.goal.revision.enabled", "true",
          "casehub.engine.goal.revision.min-outcomes", "3");
    }
  }

  @ApplicationScoped
  public static class TestGoalSignalStore extends InMemoryGoalSignalStore
      implements GoalSignalStore {}

  @ApplicationScoped
  public static class TestGoalEvolution extends DefaultGoalEvolution implements GoalEvolution {}

  @ApplicationScoped
  public static class TestAgentRegistry implements AgentRegistry {
    private final ConcurrentHashMap<String, AgentDescriptor> descriptors =
        new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AgentDescriptor> registered = new CopyOnWriteArrayList<>();

    @Override
    public void register(AgentDescriptor descriptor) {
      descriptors.put(descriptor.agentId() + "|" + descriptor.tenancyId(), descriptor);
      registered.add(descriptor);
    }

    @Override
    public Optional<AgentDescriptor> findById(String agentId, String tenancyId) {
      return Optional.ofNullable(descriptors.get(agentId + "|" + tenancyId));
    }

    @Override
    public List<AgentMatch> find(AgentQuery query) {
      return List.of();
    }

    public void seed(AgentDescriptor descriptor) {
      descriptors.put(descriptor.agentId() + "|" + descriptor.tenancyId(), descriptor);
    }

    public List<AgentDescriptor> registrations() {
      return List.copyOf(registered);
    }

    public void reset() {
      descriptors.clear();
      registered.clear();
    }
  }

  @ApplicationScoped
  public static class GoalRevisionCaseHub extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("code-review")
            .inputSchema("{ task: .task }")
            .outputSchema(".")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-goal-revision")
          .name("Goal Revision Test")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("review-worker")
                  .capabilityName("code-review")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("result", "reviewed"))))
                  .executionPolicy(new ExecutionPolicy(5000, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".task != null"))
                  .build())
          .agentDescriptor(
              "review-worker",
              AgentDescriptor.builder()
                  .agentId("agent-rev-1")
                  .name("review-agent")
                  .slot("default")
                  .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
                  .goals(
                      List.of(
                          new AgentGoal(
                              "find-bugs",
                              "Find bugs in code",
                              GoalPriority.SECONDARY,
                              Visibility.PUBLIC,
                              List.of())))
                  .build())
          .build();
    }
  }
}
