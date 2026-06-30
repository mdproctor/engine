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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.context.PropagationContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerExecutionContextTest {

  @AfterEach
  void cleanup() {
    WorkerExecutionContext.clear();
  }

  @Test
  void current_returnsNullWhenNothingSet() {
    assertThat(WorkerExecutionContext.current()).isNull();
  }

  @Test
  void current_returnsContextAfterSet() {
    WorkerContext ctx = minimalContext(UUID.randomUUID());
    WorkerExecutionContext.set(ctx);
    assertThat(WorkerExecutionContext.current()).isSameAs(ctx);
  }

  @Test
  void clear_removesContext() {
    WorkerExecutionContext.set(minimalContext(UUID.randomUUID()));
    WorkerExecutionContext.clear();
    assertThat(WorkerExecutionContext.current()).isNull();
  }

  @Test
  void context_isThreadLocal_otherThreadSeesNull() throws InterruptedException {
    WorkerExecutionContext.set(minimalContext(UUID.randomUUID()));

    AtomicReference<WorkerContext> seenInOtherThread = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Thread other =
        new Thread(
            () -> {
              seenInOtherThread.set(WorkerExecutionContext.current());
              latch.countDown();
            });
    other.start();
    latch.await();

    assertThat(seenInOtherThread.get())
        .as("other thread must not see the context set on this thread")
        .isNull();
  }

  @Test
  void set_replacesExistingContext() {
    WorkerContext first = minimalContext(UUID.randomUUID());
    WorkerContext second = minimalContext(UUID.randomUUID());
    WorkerExecutionContext.set(first);
    WorkerExecutionContext.set(second);
    assertThat(WorkerExecutionContext.current()).isSameAs(second);
  }

  @Test
  void current_exposesChannelsFromContext() {
    UUID caseId = UUID.randomUUID();
    CaseChannel channel =
        new CaseChannel(caseId + "/coord", "coord", "coordination", "none", Map.of());
    WorkerContext ctx =
        new WorkerContext(
            "desc", caseId, List.of(channel), List.of(), PropagationContext.createRoot(), Map.of());
    WorkerExecutionContext.set(ctx);

    assertThat(WorkerExecutionContext.current().channels()).containsExactly(channel);
  }

  @Test
  void runtimeThreadLocal_setAndClear() {
    assertThat(WorkerExecutionContext.currentRuntime()).isNull();

    io.casehub.api.engine.WorkerRuntime mockRuntime =
        new io.casehub.api.engine.WorkerRuntime() {
          @Override
          public UUID caseId() {
            return UUID.fromString("00000000-0000-0000-0000-000000000001");
          }

          @Override
          public io.casehub.worker.api.WorkerResult execute(
              io.casehub.worker.api.WorkerFunction f, Map<String, Object> i) {
            return null;
          }

          @Override
          public io.casehub.worker.api.WorkerResult execute(String n, Map<String, Object> i) {
            return null;
          }

          @Override
          public UUID spawnCase(String t, Map<String, Object> i) {
            return null;
          }

          @Override
          public io.casehub.api.context.CaseContext awaitCase(UUID id, java.time.Duration t) {
            return null;
          }

          @Override
          public io.casehub.api.context.CaseContext spawnAndAwaitCase(
              String t, Map<String, Object> i, java.time.Duration d) {
            return null;
          }
        };

    WorkerExecutionContext.setRuntime(mockRuntime);
    assertThat(WorkerExecutionContext.currentRuntime()).isSameAs(mockRuntime);

    WorkerExecutionContext.clear();
    assertThat(WorkerExecutionContext.currentRuntime()).isNull();
  }

  private WorkerContext minimalContext(UUID caseId) {
    return new WorkerContext(
        "task", caseId, null, List.of(), PropagationContext.createRoot(), Map.of());
  }
}
