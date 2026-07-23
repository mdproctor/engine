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
package io.casehub.engine;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class CustomGoalKindCompletionTest {

  @Inject CustomGoalKindBean bean;

  @Inject CaseHubRuntime runtime;

  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void customGoalKind_escalated_reachesFaultedState() {
    UUID caseId = bean.startCase(Map.of());

    assertEquals(CaseStatus.RUNNING, caseInstanceCache.get(caseId).getState());

    bean.signal(caseId, "escalate", true);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    CaseStatus.FAULTED,
                    caseInstanceCache.get(caseId).getState(),
                    "Custom goal kind ESCALATED must transition case to FAULTED"));
  }

  @Test
  void standardSuccessGoal_stillCompletesNormally() {
    UUID caseId = bean.startCase(Map.of());

    assertEquals(CaseStatus.RUNNING, caseInstanceCache.get(caseId).getState());

    bean.signal(caseId, "done", true);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    CaseStatus.COMPLETED,
                    caseInstanceCache.get(caseId).getState(),
                    "Standard SUCCESS goal must still transition case to COMPLETED"));
  }

  @Test
  void escalatedGoal_winsOverSuccess_byInsertionOrder() {
    UUID caseId = bean.startCase(Map.of());

    assertEquals(CaseStatus.RUNNING, caseInstanceCache.get(caseId).getState());

    runtime.signal(caseId, Map.of("done", true, "escalate", true));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    CaseStatus.FAULTED,
                    caseInstanceCache.get(caseId).getState(),
                    "ESCALATED is listed before SUCCESS — first match wins, so FAULTED"));
  }

  @ApplicationScoped
  public static class CustomGoalKindBean extends CaseHub {

    static final GoalKind ESCALATED = GoalKind.of("escalated", CaseStatus.FAULTED);

    private final Goal escalationGoal =
        Goal.builder()
            .name("needs-escalation")
            .condition(".escalate == true")
            .kind("escalated")
            .build();

    private final Goal successGoal =
        Goal.builder().name("done").condition(".done == true").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-custom-goal")
          .name("Custom Goal Kind Test")
          .version("1.0.0")
          .goals(escalationGoal, successGoal)
          .completion(
              GoalBasedCompletion.builder()
                  .goal(ESCALATED, GoalExpression.allOf(escalationGoal))
                  .goal(StandardGoalKind.SUCCESS, GoalExpression.allOf(successGoal))
                  .build())
          .build();
    }
  }
}
