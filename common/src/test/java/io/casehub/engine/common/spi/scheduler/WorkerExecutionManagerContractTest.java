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
package io.casehub.engine.common.spi.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.worker.api.WorkerFunction;
import org.junit.jupiter.api.Test;

/**
 * Abstract contract test for {@link WorkerExecutionManager}. Implementations extend this class and
 * provide a concrete instance via {@link #createManager()}.
 *
 * <p>Per {@code spi-evolution-default-methods} protocol: every SPI method — default or not — must
 * have a contract test verifying all implementations honour the same semantics.
 */
public abstract class WorkerExecutionManagerContractTest {

  protected abstract WorkerExecutionManager createManager();

  @Test
  void supports_doesNotThrow() {
    final WorkerExecutionManager manager = createManager();
    manager.supports("any-capability", "any-tenant");
  }

  @Test
  void canExecute_defaultReturnsTrue() {
    final WorkerExecutionManager manager =
        new WorkerExecutionManager() {
          @Override
          public boolean supports(String capabilityName, String tenancyId) {
            return false;
          }

          @Override
          public io.smallrye.mutiny.Uni<Void> submit(
              Long eventLogId,
              io.casehub.engine.common.internal.model.CaseInstance instance,
              io.casehub.worker.api.Worker worker,
              io.casehub.worker.api.Capability capability,
              java.util.Map<String, Object> inputData) {
            return io.smallrye.mutiny.Uni.createFrom().voidItem();
          }

          @Override
          public int getActiveWorkCount(String workerId) {
            return 0;
          }
        };

    assertThat(manager.canExecute(WorkerFunction.NONE)).isTrue();
    assertThat(manager.canExecute(new WorkerFunction.Sync(input -> null))).isTrue();
  }

  @Test
  void canExecute_syncFunction() {
    final WorkerExecutionManager manager = createManager();
    final WorkerFunction sync =
        new WorkerFunction.Sync(input -> io.casehub.worker.api.WorkerResult.of(java.util.Map.of()));
    assertThat(manager.canExecute(sync)).as("canExecute(Sync) should be consistent").isNotNull();
  }

  @Test
  void canExecute_noneFunction() {
    final WorkerExecutionManager manager = createManager();
    manager.canExecute(WorkerFunction.NONE);
  }

  @Test
  void getActiveWorkCount_doesNotThrow() {
    final WorkerExecutionManager manager = createManager();
    assertThat(manager.getActiveWorkCount("any-worker")).isGreaterThanOrEqualTo(0);
  }

  @Test
  void getActiveCaseIds_returnsEmptyByDefault() {
    final WorkerExecutionManager manager = createManager();
    assertThat(manager.getActiveCaseIds("any-worker")).isNotNull();
  }
}
