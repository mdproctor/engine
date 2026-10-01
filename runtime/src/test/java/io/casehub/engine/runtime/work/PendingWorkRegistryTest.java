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
package io.casehub.engine.internal.work;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.WorkResult;
import io.casehub.api.model.WorkStatus;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PendingWorkRegistryTest {

  // ---- happy path -----------------------------------------------------------

  @Test
  void register_thenComplete_futureResolves() throws Exception {
    PendingWorkRegistry registry =
        new PendingWorkRegistry(
            org.mockito.Mockito.mock(
                io.casehub.engine.common.spi.CrossTenantEventLogRepository.class));
    CompletableFuture<WorkResult> future = registry.register("key-1");

    WorkResult result = WorkResult.completed("key-1", Map.of("output", "done"), "worker-a");
    registry.complete("key-1", result);

    assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(result);
  }

  @Test
  void complete_withNoRegisteredFuture_doesNotThrow() {
    PendingWorkRegistry registry =
        new PendingWorkRegistry(
            org.mockito.Mockito.mock(
                io.casehub.engine.common.spi.CrossTenantEventLogRepository.class));
    WorkResult result = WorkResult.completed("unknown-key", Map.of(), "worker-a");
    registry.complete("unknown-key", result); // must not throw
  }

  // ---- correctness ----------------------------------------------------------

  @Test
  void afterComplete_futureIsRemovedFromRegistry() throws Exception {
    PendingWorkRegistry registry =
        new PendingWorkRegistry(
            org.mockito.Mockito.mock(
                io.casehub.engine.common.spi.CrossTenantEventLogRepository.class));
    registry.register("key-2");
    registry.complete("key-2", WorkResult.completed("key-2", Map.of(), "w"));

    assertThat(registry.hasPending("key-2")).isFalse();
  }

  @Test
  void multipleKeys_completedIndependently() throws Exception {
    PendingWorkRegistry registry =
        new PendingWorkRegistry(
            org.mockito.Mockito.mock(
                io.casehub.engine.common.spi.CrossTenantEventLogRepository.class));
    CompletableFuture<WorkResult> f1 = registry.register("key-a");
    CompletableFuture<WorkResult> f2 = registry.register("key-b");

    registry.complete("key-a", WorkResult.faulted("key-a", "worker-x"));

    assertThat(f1.isDone()).isTrue();
    assertThat(f1.get().status()).isEqualTo(WorkStatus.FAULTED);
    assertThat(f2.isDone()).isFalse();
  }

  // ---- robustness -----------------------------------------------------------

  @Test
  void registerSameKeyTwice_bothFuturesComplete() throws Exception {
    PendingWorkRegistry registry =
        new PendingWorkRegistry(
            org.mockito.Mockito.mock(
                io.casehub.engine.common.spi.CrossTenantEventLogRepository.class));
    CompletableFuture<WorkResult> f1 = registry.register("dup-key");
    CompletableFuture<WorkResult> f2 = registry.register("dup-key");

    WorkResult result = WorkResult.completed("dup-key", Map.of(), "w");
    registry.complete("dup-key", result);

    assertThat(f1.get(1, TimeUnit.SECONDS)).isEqualTo(result);
    assertThat(f2.get(1, TimeUnit.SECONDS)).isEqualTo(result);
  }
}
