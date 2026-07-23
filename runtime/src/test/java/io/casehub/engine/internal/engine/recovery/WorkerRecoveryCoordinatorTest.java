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
package io.casehub.engine.internal.engine.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerRecoveryCoordinatorTest {

  @Test
  void initialStatus_isPending() {
    var coordinator = new WorkerRecoveryCoordinator(serviceWith(() -> {}), Duration.ofSeconds(60));
    assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.PENDING);
  }

  @Test
  void successfulRecovery_transitionsToCompleted() {
    var coordinator = new WorkerRecoveryCoordinator(serviceWith(() -> {}), Duration.ofSeconds(60));

    coordinator.triggerRecovery();

    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.COMPLETED));
  }

  @Test
  void failedRecovery_transitionsToFailed() {
    var coordinator =
        new WorkerRecoveryCoordinator(
            serviceWith(
                () -> {
                  throw new RuntimeException("DB down");
                }),
            Duration.ofSeconds(60));

    coordinator.triggerRecovery();

    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.FAILED));
  }

  @Test
  void hungRecovery_transitionsToFailedAfterTimeout() {
    var coordinator =
        new WorkerRecoveryCoordinator(
            serviceWith(
                () -> {
                  try {
                    Thread.sleep(10_000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }),
            Duration.ofMillis(100));

    coordinator.triggerRecovery();

    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () -> assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.FAILED));
  }

  private WorkerExecutionRecoveryService serviceWith(Runnable recoveryAction) {
    return new WorkerExecutionRecoveryService() {
      @Override
      public CaseInstance loadOrRestoreCaseInstance(UUID caseId) {
        return null;
      }

      @Override
      public void recoverPendingScheduledWorkers() {
        recoveryAction.run();
      }
    };
  }
}
