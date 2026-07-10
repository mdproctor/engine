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
package io.casehub.blackboard.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SequentialStrategyIntegrationTest {

  @Inject CaseInstanceCache cache;
  @Inject SequentialCaseBean sequentialBean;

  @BeforeEach
  void setUp() {
    SequentialCaseBean.executionOrder.clear();
  }

  @Test
  void sequentialStrategy_firesBindingsOneAtATime() {
    UUID caseId = sequentialBean.startCase(Map.of("trigger", true)).toCompletableFuture().join();

    await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(cache.get(caseId).getState()).isEqualTo(CaseStatus.COMPLETED);
              assertThat(cache.get(caseId).getCaseContext().get("step1")).isEqualTo(true);
              assertThat(cache.get(caseId).getCaseContext().get("step2")).isEqualTo(true);
              assertThat(cache.get(caseId).getCaseContext().get("step3")).isEqualTo(true);
              assertThat(SequentialCaseBean.executionOrder)
                  .containsExactly("step1", "step2", "step3");
            });
  }

  @ApplicationScoped
  public static class SequentialCaseBean extends CaseHub {
    static final List<String> executionOrder = new CopyOnWriteArrayList<>();

    @Override
    public CaseDefinition getDefinition() {
      Capability cap1 =
          Capability.builder().name("step1").inputSchema(".").outputSchema(".").build();
      Capability cap2 =
          Capability.builder().name("step2").inputSchema(".").outputSchema(".").build();
      Capability cap3 =
          Capability.builder().name("step3").inputSchema(".").outputSchema(".").build();

      Worker w1 =
          Worker.builder()
              .name("worker1")
              .capabilityName("step1")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      input -> {
                        executionOrder.add("step1");
                        return WorkerResult.of(Map.of("step1", true));
                      }))
              .build();

      Worker w2 =
          Worker.builder()
              .name("worker2")
              .capabilityName("step2")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      input -> {
                        executionOrder.add("step2");
                        return WorkerResult.of(Map.of("step2", true));
                      }))
              .build();

      Worker w3 =
          Worker.builder()
              .name("worker3")
              .capabilityName("step3")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      input -> {
                        executionOrder.add("step3");
                        return WorkerResult.of(Map.of("step3", true));
                      }))
              .build();

      return CaseDefinition.builder()
          .namespace("test-sequential")
          .name("Sequential Strategy Test")
          .version("1.0.0")
          .planningStrategy("sequential")
          .capabilities(cap1, cap2, cap3)
          .workers(w1, w2, w3)
          .bindings(
              Binding.builder()
                  .name("b1")
                  .capability(cap1)
                  .on(new ContextChangeTrigger(".trigger == true"))
                  .build(),
              Binding.builder()
                  .name("b2")
                  .capability(cap2)
                  .on(new ContextChangeTrigger(".trigger == true"))
                  .build(),
              Binding.builder()
                  .name("b3")
                  .capability(cap3)
                  .on(new ContextChangeTrigger(".trigger == true"))
                  .build())
          .goals(
              Goal.builder()
                  .name("done")
                  .kind(GoalKind.SUCCESS)
                  .condition(new JQExpressionEvaluator(".step3 == true"))
                  .build())
          .completion(
              GoalExpression.allOf(
                  Goal.builder()
                      .name("done")
                      .kind(GoalKind.SUCCESS)
                      .condition(new JQExpressionEvaluator(".step3 == true"))
                      .build()))
          .build();
    }
  }
}
