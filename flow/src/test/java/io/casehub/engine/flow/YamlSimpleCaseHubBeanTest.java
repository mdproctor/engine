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
package io.casehub.engine.flow;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class YamlSimpleCaseHubBeanTest {

  @Inject YamlSimpleCaseHubBean yamlSimpleCaseHubBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  public void test() {
    CaseDefinition def = yamlSimpleCaseHubBean.getDefinition();
    assertNotNull(def);

    // name
    assertEquals("Document Processing Test (YAML)", def.getName());

    // capabilities
    assertEquals(1, def.getCapabilities().size());
    assertEquals("processDocument", def.getCapabilities().iterator().next().name());

    // workers
    assertEquals(1, def.getWorkers().size());
    assertEquals("document-processor", def.getWorkers().get(0).name());

    // bindings
    assertEquals(1, def.getBindings().size());
    CapabilityTarget capTarget = (CapabilityTarget) def.getBindings().get(0).target();
    assertEquals("processDocument", capTarget.capability().name());

    // milestones
    assertEquals(1, def.getMilestones().size());
    assertEquals("documentProcessed", def.getMilestones().get(0).getName());
    assertEquals(
        ".status == \"processed\"",
        ((JQExpressionEvaluator) def.getMilestones().get(0).getCompletionCriteria()).expression());

    // goals
    assertEquals(1, def.getGoals().size());
    assertEquals("documentProcessingComplete", def.getGoals().get(0).getName());
    assertEquals(
        ".status == \"processed\"",
        ((JQExpressionEvaluator) def.getGoals().get(0).getCondition()).expression());

    // completion
    assertNotNull(def.getCompletion());
    assertInstanceOf(GoalBasedCompletion.class, def.getCompletion());
    GoalBasedCompletion<?> completion = (GoalBasedCompletion<?>) def.getCompletion();
    assertEquals(1, completion.getGoals().size());
    var successExpr = completion.getGoals().get(StandardGoalKind.SUCCESS);
    assertNotNull(successExpr);
    assertInstanceOf(AllOfGoalExpression.class, successExpr);
    AllOfGoalExpression allOf = (AllOfGoalExpression) successExpr;
    assertEquals(1, allOf.children().size());
    assertTrue(successExpr.goalNames().contains("documentProcessingComplete"));
  }

  @Test
  public void testExecution() {
    Map<String, Object> initialContext =
        Map.of(
            "documentId", "doc-456",
            "status", "processing");

    UUID caseId = yamlSimpleCaseHubBean.startCase(initialContext);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertNotNull(instance);
              assertEquals(CaseStatus.COMPLETED, instance.getState());
            });
  }
}
