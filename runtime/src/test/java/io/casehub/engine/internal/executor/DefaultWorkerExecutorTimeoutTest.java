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
package io.casehub.engine.internal.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DefaultWorkerExecutorTimeoutTest {

  @Inject WorkerExecutor workerExecutor;

  @Test
  void timeout_produces_expired_outcome_not_exception() {
    WorkerFunction.Sync slowWorker =
        new WorkerFunction.Sync(
            input -> {
              try {
                Thread.sleep(5000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return WorkerResult.of(Map.of("result", "late"));
            });

    WorkerContext context =
        new WorkerContext(
            "test-worker",
            UUID.randomUUID(),
            null,
            null,
            io.casehub.api.context.PropagationContext.createRoot(),
            null);

    WorkerResult result =
        workerExecutor
            .execute(
                slowWorker,
                Map.of(),
                context,
                200, // 200ms timeout — worker sleeps 5s
                null,
                new ExecutionMetadata("test-worker", "hash-1"))
            .await()
            .atMost(Duration.ofSeconds(10));

    assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    assertThat(((WorkerOutcome.Expired) result.outcome()).reason()).contains("200ms");
    assertThat(result.output()).isEmpty();
  }
}
