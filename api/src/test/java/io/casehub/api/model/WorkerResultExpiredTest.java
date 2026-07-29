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

import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerResultExpiredTest {

  @Test
  void expired_factory_creates_expired_outcome_with_empty_output() {
    WorkerResult<?> result = WorkerResult.expired("timed out");
    assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    assertThat(((WorkerOutcome.Expired<?>) result.outcome()).reason()).isEqualTo("timed out");
    assertThat(result.output()).isNull();
    assertThat(result.outcome()).isNotInstanceOf(WorkerOutcome.Success.class);
  }

  @Test
  void expired_factory_with_partial_output() {
    Map<String, Object> partial = Map.of("progress", "50%");
    WorkerResult<?> result = WorkerResult.expired("deadline passed", partial);
    assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    assertThat(result.output()).isEqualTo(partial);
  }

  @Test
  void expired_outcome_cannot_carry_planned_action() {
    WorkerOutcome expired = new WorkerOutcome.Expired("timed out");
    assertThat(expired).isNotInstanceOf(WorkerOutcome.Success.class);
  }

  @Test
  void expired_is_sealed_variant() {
    WorkerOutcome outcome = new WorkerOutcome.Expired("reason");
    assertThat(outcome).isInstanceOf(WorkerOutcome.class);
    String matched =
        switch (outcome) {
          case WorkerOutcome.Success s -> "success";
          case WorkerOutcome.Completed c -> "completed";
          case WorkerOutcome.Declined d -> "declined";
          case WorkerOutcome.Failed f -> "failed";
          case WorkerOutcome.Expired e -> "expired:" + e.reason();
          case WorkerOutcome.Completed c -> "completed";
        };
    assertThat(matched).isEqualTo("expired:reason");
  }

  @Test
  void work_result_expired_factory() {
    WorkResult result = WorkResult.expired("hash-123", "worker-1", UUID.randomUUID());
    assertThat(result.status()).isEqualTo(WorkStatus.EXPIRED);
    assertThat(result.correlationKey()).isEqualTo("hash-123");
    assertThat(result.workerId()).isEqualTo("worker-1");
    assertThat(result.output()).isEmpty();
  }
}
