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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CaseEvaluationSerializerTest {

  private final CaseEvaluationSerializer serializer = new CaseEvaluationSerializer();

  @Test
  void runsEvaluatorImmediatelyWhenIdle() {
    AtomicInteger count = new AtomicInteger();
    serializer.submit(UUID.randomUUID(), count::incrementAndGet);
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void serialisesEvaluationsForSameCase() throws Exception {
    UUID caseId = UUID.randomUUID();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch firstCanProceed = new CountDownLatch(1);
    AtomicInteger maxConcurrent = new AtomicInteger();
    AtomicInteger running = new AtomicInteger();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> serializer.submit(caseId, () -> {
        int r = running.incrementAndGet();
        maxConcurrent.updateAndGet(cur -> Math.max(cur, r));
        firstStarted.countDown();
        awaitQuietly(firstCanProceed);
        running.decrementAndGet();
      }));

      assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<Void> second = CompletableFuture.runAsync(
          () -> serializer.submit(caseId, () -> {
            int r = running.incrementAndGet();
            maxConcurrent.updateAndGet(cur -> Math.max(cur, r));
            running.decrementAndGet();
          }),
          executor);

      Thread.sleep(100);
      assertThat(second.isDone()).isFalse();

      firstCanProceed.countDown();
      second.get(2, TimeUnit.SECONDS);

      assertThat(maxConcurrent.get()).isEqualTo(1);
    }
  }

  @Test
  void coalescesMultiplePendingEvents() throws Exception {
    UUID caseId = UUID.randomUUID();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch firstCanProceed = new CountDownLatch(1);
    AtomicInteger totalEvaluations = new AtomicInteger();
    AtomicReference<String> lastEvaluated = new AtomicReference<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> serializer.submit(caseId, () -> {
        totalEvaluations.incrementAndGet();
        firstStarted.countDown();
        awaitQuietly(firstCanProceed);
      }));

      assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<Void> f2 = CompletableFuture.runAsync(
          () -> serializer.submit(caseId, () -> {
            totalEvaluations.incrementAndGet();
            lastEvaluated.set("second");
          }),
          executor);

      Thread.sleep(50);

      CompletableFuture<Void> f3 = CompletableFuture.runAsync(
          () -> serializer.submit(caseId, () -> {
            totalEvaluations.incrementAndGet();
            lastEvaluated.set("third");
          }),
          executor);

      Thread.sleep(50);
      firstCanProceed.countDown();

      CompletableFuture.allOf(f2, f3).get(2, TimeUnit.SECONDS);

      assertThat(totalEvaluations.get()).isEqualTo(2);
      assertThat(lastEvaluated.get()).isEqualTo("third");
    }
  }

  @Test
  void allowsConcurrentEvaluationsForDifferentCases() throws Exception {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();
    CountDownLatch bothRunning = new CountDownLatch(2);
    CountDownLatch proceed = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> serializer.submit(case1, () -> {
        bothRunning.countDown();
        awaitQuietly(proceed);
      }));
      executor.submit(() -> serializer.submit(case2, () -> {
        bothRunning.countDown();
        awaitQuietly(proceed);
      }));

      assertThat(bothRunning.await(2, TimeUnit.SECONDS)).isTrue();
      proceed.countDown();
    }
  }

  @Test
  void evictCleansUpState() {
    UUID caseId = UUID.randomUUID();
    AtomicInteger count = new AtomicInteger();
    serializer.submit(caseId, count::incrementAndGet);
    serializer.evict(caseId);
    serializer.submit(caseId, count::incrementAndGet);
    assertThat(count.get()).isEqualTo(2);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
