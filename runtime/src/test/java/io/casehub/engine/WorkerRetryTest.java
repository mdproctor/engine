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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class WorkerRetryTest {

  @Inject CaseHubBean bean;

  @Test
  public void test() {
    AtomicReference<UUID> ref = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    Map<String, Object> initialContext =
        Map.of(
            "documentId", "doc-123",
            "status", "processing");

    bean.startCase(initialContext)
        .thenAccept(ref::set)
        .exceptionally(
            ex -> {
              err.set(ex);
              return null;
            });

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertNotNull(ref.get());
            });

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertTrue(
                  CaseHubBean.runCount.get() >= 3,
                  "Expected at least 3 attempts (2 failures + 1 success), got "
                      + CaseHubBean.runCount.get());
            });
  }

  @ApplicationScoped
  public static class CaseHubBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger(0);

    Capability capability =
        Capability.builder()
            .name("processDocument")
            .inputSchema("{ documentId: .documentId, status: .status }")
            .outputSchema("{ processedDocument: ., status: .status }")
            .description("Process a document from the case context")
            .build();

    Goal goal =
        Goal.builder()
            .name("documentProcessingComplete")
            .condition(".status == \"processed\"")
            .kind(GoalKind.SUCCESS)
            .description("Goal achieved when document processing is complete")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-retry")
          .name("Worker Retry Test")
          .version("1.0.0")
          .title("Test Case with Worker and Capability")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("document-processor")
                  .capabilityName("processDocument")
                  .function(
                      new Function<>() {
                        @Override
                        public WorkerResult apply(Map<String, Object> stateContext) {
                          if (runCount.incrementAndGet() <= 2) {
                            throw new RuntimeException("Simulated processing failure");
                          }

                          return WorkerResult.of(
                              Map.of(
                                  "processedDocument",
                                  Map.of(
                                      "id",
                                      stateContext.get("documentId"),
                                      "content",
                                      "Processed content for document "
                                          + stateContext.get("documentId")),
                                  "status",
                                  "processed"));
                        }
                      })
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(3, 1000)))
                  .description("Processes documents and updates case context")
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-processing-status")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"processing\""))
                  .build())
          .milestones(
              Milestone.builder()
                  .name("documentProcessed")
                  .completionCriteria(".status == \"processed\"")
                  .description("Milestone reached when document is processed")
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
