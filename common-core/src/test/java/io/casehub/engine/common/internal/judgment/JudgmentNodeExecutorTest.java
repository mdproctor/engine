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
package io.casehub.engine.common.internal.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.JudgmentTarget;
import io.casehub.engine.common.spi.JudgmentNodeResult;
import io.casehub.engine.common.spi.JudgmentResponse;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JudgmentNodeExecutorTest {

  private JudgmentNodeExecutor executor;
  private RecordingJudgmentScheduler scheduler;

  @BeforeEach
  void setUp() {
    executor = new JudgmentNodeExecutor();
    scheduler = new RecordingJudgmentScheduler();
    executor.judgmentScheduler = new SingletonInstance<>(scheduler);
  }

  @Test
  void execute_completesWhenResponseArrives() throws Exception {
    UUID caseId = UUID.randomUUID();
    String binding = "review-tx";
    JudgmentScheduleRequest request = request(caseId, binding);

    CompletableFuture<JudgmentResponse> future =
        CompletableFuture.supplyAsync(() -> executor.execute(request, Duration.ofSeconds(5)));

    Thread.sleep(50);
    assertThat(executor.hasPending(caseId, binding)).isTrue();
    assertThat(scheduler.lastRequest).isNotNull();

    JudgmentResponse response =
        new JudgmentResponse(
            caseId, binding, "t1", "approve", Map.of("rationale", "ok"), "user-1", "HUMAN");
    executor.enqueue(caseId, binding, new JudgmentNodeResult.Completed(response));

    JudgmentResponse result = future.get(2, TimeUnit.SECONDS);
    assertThat(result.decision()).isEqualTo("approve");
    assertThat(result.callerId()).isEqualTo("user-1");
    assertThat(executor.hasPending(caseId, binding)).isFalse();
  }

  @Test
  void execute_throwsOnTimeout() {
    UUID caseId = UUID.randomUUID();
    JudgmentScheduleRequest request = request(caseId, "timeout-test");

    assertThatThrownBy(() -> executor.execute(request, Duration.ofMillis(50)))
        .isInstanceOf(JudgmentTimeoutException.class)
        .hasMessageContaining("timeout-test");
  }

  @Test
  void execute_throwsOnFault() throws Exception {
    UUID caseId = UUID.randomUUID();
    String binding = "fault-test";
    JudgmentScheduleRequest request = request(caseId, binding);

    CompletableFuture<JudgmentResponse> future =
        CompletableFuture.supplyAsync(() -> executor.execute(request, Duration.ofSeconds(5)));

    Thread.sleep(50);
    executor.enqueue(caseId, binding, new JudgmentNodeResult.Faulted("max escalations reached"));

    assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(JudgmentFaultException.class)
        .hasMessageContaining("max escalations reached");
  }

  @Test
  void execute_reYieldResetsTimeout() throws Exception {
    UUID caseId = UUID.randomUUID();
    String binding = "reyield-test";
    JudgmentScheduleRequest request = request(caseId, binding);

    CompletableFuture<JudgmentResponse> future =
        CompletableFuture.supplyAsync(() -> executor.execute(request, Duration.ofMillis(200)));

    Thread.sleep(50);
    executor.enqueue(caseId, binding, new JudgmentNodeResult.ReYielded());

    Thread.sleep(100);
    assertThat(executor.hasPending(caseId, binding)).isTrue();

    JudgmentResponse response =
        new JudgmentResponse(caseId, binding, "t1", "approve", Map.of(), null, null);
    executor.enqueue(caseId, binding, new JudgmentNodeResult.Completed(response));

    JudgmentResponse result = future.get(2, TimeUnit.SECONDS);
    assertThat(result.decision()).isEqualTo("approve");
  }

  @Test
  void enqueue_noOpWhenNoPendingJudgment() {
    UUID caseId = UUID.randomUUID();
    executor.enqueue(caseId, "no-pending", new JudgmentNodeResult.Faulted("ignored"));
    assertThat(executor.hasPending(caseId, "no-pending")).isFalse();
  }

  @Test
  void execute_cleansPendingInFinallyBlock() {
    UUID caseId = UUID.randomUUID();
    String binding = "cleanup-test";
    JudgmentScheduleRequest request = request(caseId, binding);

    try {
      executor.execute(request, Duration.ofMillis(10));
    } catch (JudgmentTimeoutException ignored) {
    }

    assertThat(executor.hasPending(caseId, binding)).isFalse();
  }

  // ── Helpers ──

  private static JudgmentScheduleRequest request(UUID caseId, String binding) {
    JudgmentTarget target = JudgmentTarget.builder().prompt("test").build();
    return new JudgmentScheduleRequest(caseId, "tenant-1", binding, target, Map.of(), null, null);
  }

  private static class RecordingJudgmentScheduler implements JudgmentScheduler {
    JudgmentScheduleRequest lastRequest;

    @Override
    public void schedule(JudgmentScheduleRequest request) {
      lastRequest = request;
    }
  }

  @SuppressWarnings("unchecked")
  private static class SingletonInstance<T> implements Instance<T> {
    private final T value;

    SingletonInstance(T value) {
      this.value = value;
    }

    @Override
    public T get() {
      return value;
    }

    @Override
    public boolean isResolvable() {
      return true;
    }

    @Override
    public boolean isAmbiguous() {
      return false;
    }

    @Override
    public boolean isUnsatisfied() {
      return false;
    }

    @Override
    public Instance<T> select(java.lang.annotation.Annotation... qualifiers) {
      return this;
    }

    @Override
    public <U extends T> Instance<U> select(
        Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Instance<U> select(
        jakarta.enterprise.util.TypeLiteral<U> subtype,
        java.lang.annotation.Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void destroy(T instance) {}

    @Override
    public Handle<T> getHandle() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<Handle<T>> handles() {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Iterator<T> iterator() {
      return java.util.List.of(value).iterator();
    }
  }
}
