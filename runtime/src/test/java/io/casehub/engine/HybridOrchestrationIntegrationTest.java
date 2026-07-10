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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HybridOrchestrationIntegrationTest {

  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceCache cache;
  @Inject SignalAwaitBean signalAwaitBean;
  @Inject SpawnParentBean spawnParentBean;

  // signalAndAwait test
  @Test
  void signalAndAwait_resolvesAfterWorkerCompletes() throws Exception {
    UUID caseId = signalAwaitBean.startCase(Map.of()).toCompletableFuture().join();

    var settled =
        runtime
            .signalAndAwait(caseId, Map.of("trigger", "go"), Duration.ofSeconds(10))
            .toCompletableFuture();

    var result = settled.get(15, TimeUnit.SECONDS);
    assertThat(result.get("result")).isEqualTo("done");
  }

  @ApplicationScoped
  public static class SignalAwaitBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema(".")
              .outputSchema("{ result: \"done\" }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilityName("doWork")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class, input -> WorkerResult.of(Map.of("result", "done"))))
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("SignalAwait")
          .version("1.0.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("trigger")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".trigger != null"))
                  .build())
          .build();
    }
  }

  // --- spawnCase/awaitCase integration test ---

  @Test
  void spawnAndAwait_childCaseCompletesAndReturnsContext() {
    UUID parentCaseId =
        spawnParentBean.startCase(Map.of("trigger", true)).toCompletableFuture().join();

    await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(cache.get(parentCaseId).getState()).isEqualTo(CaseStatus.COMPLETED);
              assertThat(cache.get(parentCaseId).getCaseContext().get("childResult"))
                  .isEqualTo("done");
            });
  }

  @ApplicationScoped
  public static class SpawnChildBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder().name("childWork").inputSchema(".").outputSchema(".").build();

      Worker worker =
          Worker.builder()
              .name("childWorker")
              .capabilityName("childWork")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class, input -> WorkerResult.of(Map.of("result", "done"))))
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("SpawnChild")
          .version("1.0.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("doChildWork")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".trigger == true"))
                  .build())
          .goals(
              Goal.builder()
                  .name("childDone")
                  .kind(GoalKind.SUCCESS)
                  .condition(new JQExpressionEvaluator(".result == \"done\""))
                  .build())
          .completion(
              GoalExpression.allOf(
                  Goal.builder()
                      .name("childDone")
                      .kind(GoalKind.SUCCESS)
                      .condition(new JQExpressionEvaluator(".result == \"done\""))
                      .build()))
          .build();
    }
  }

  @ApplicationScoped
  public static class SpawnParentBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder().name("parentOrchestrate").inputSchema(".").outputSchema(".").build();

      Worker worker =
          Worker.builder()
              .name("parentWorker")
              .capabilityName("parentOrchestrate")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      input -> {
                        WorkerRuntime rt = WorkerExecutionContext.currentRuntime();
                        var childCtx =
                            rt.spawnAndAwaitCase(
                                "SpawnChild", Map.of("trigger", true), Duration.ofSeconds(10));
                        Object childResult = childCtx.get("result");
                        return WorkerResult.of(
                            Map.of("childResult", childResult != null ? childResult : "missing"));
                      }))
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("SpawnParent")
          .version("1.0.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("orchestrate")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".trigger == true"))
                  .build())
          .goals(
              Goal.builder()
                  .name("parentDone")
                  .kind(GoalKind.SUCCESS)
                  .condition(new JQExpressionEvaluator(".childResult == \"done\""))
                  .build())
          .completion(
              GoalExpression.allOf(
                  Goal.builder()
                      .name("parentDone")
                      .kind(GoalKind.SUCCESS)
                      .condition(new JQExpressionEvaluator(".childResult == \"done\""))
                      .build()))
          .build();
    }
  }
}
