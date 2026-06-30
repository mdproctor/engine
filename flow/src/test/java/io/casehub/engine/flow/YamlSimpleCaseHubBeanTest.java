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

import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.WorkerFunction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class YamlSimpleCaseHubBeanTest {

  @Inject YamlSimpleCaseHubBean yamlSimpleCaseHubBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  public void test() {
    CaseDefinition def = yamlSimpleCaseHubBean.getDefinition();
    assertNotNull(def);

    assertEquals("0.1", def.getDsl());
    assertEquals("1.0.0", def.getVersion());
    assertEquals("Document Processing Test (YAML)", def.getName());
    assertEquals("test-yaml", def.getNamespace());
    assertEquals("Test Case with Worker and Capability", def.getTitle());

    // capabilities
    assertEquals(1, def.getCapabilities().size());
    assertEquals("processDocument", def.getCapabilities().get(0).name());
    assertEquals(
        "{ documentId: .documentId, status: .status }", def.getCapabilities().get(0).inputSchema());
    assertEquals(
        "{ processedDocument: ., status: .status }", def.getCapabilities().get(0).outputSchema());

    // workers
    assertEquals(1, def.getWorkers().size());
    assertEquals("document-processor", def.getWorkers().get(0).name());
    assertEquals(1, def.getWorkers().get(0).capabilityNames().size());
    assertEquals("processDocument", def.getWorkers().get(0).capabilityNames().iterator().next());
    assertInstanceOf(WorkerFunction.class, def.getWorkers().get(0).function());

    // rules
    assertEquals(1, def.getBindings().size());
    assertEquals("trigger-on-processing-status", def.getBindings().get(0).getName());
    assertInstanceOf(
        io.casehub.api.model.CapabilityTarget.class, def.getBindings().get(0).target());
    assertEquals(
        "processDocument",
        ((io.casehub.api.model.CapabilityTarget) def.getBindings().get(0).target())
            .capability()
            .name());
    assertInstanceOf(ContextChangeTrigger.class, def.getBindings().get(0).getOn());
    ContextChangeTrigger cct = (ContextChangeTrigger) def.getBindings().get(0).getOn();
    assertEquals(
        ".status == \"processing\"", ((JQExpressionEvaluator) cct.getFilter()).expression());

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
    GoalBasedCompletion completion = (GoalBasedCompletion) def.getCompletion();
    assertNotNull(completion.getSuccess());
    assertInstanceOf(AllOfGoalExpression.class, completion.getSuccess());
    assertEquals(1, completion.getSuccess().getGoals().size());
    assertEquals(
        "documentProcessingComplete",
        completion.getSuccess().getGoals().iterator().next().getName());
  }

  @Test
  public void testExecution() {
    AtomicReference<UUID> ref = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    Map<String, Object> initialContext =
        Map.of(
            "documentId", "doc-456",
            "status", "processing");

    yamlSimpleCaseHubBean
        .startCase(initialContext)
        .thenAccept(ref::set)
        .exceptionally(
            ex -> {
              err.set(ex);
              return null;
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertNotNull(ref.get());
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(ref.get());
              assertNotNull(instance);
              assertEquals(CaseStatus.COMPLETED, instance.getState());
            });
  }
}
