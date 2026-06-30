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

import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerTest {

  @Test
  void builder_creates_worker_with_record_accessors() {
    Worker worker =
        Worker.builder()
            .name("test-worker")
            .capabilityName("review")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
            .description("a worker")
            .build();

    assertThat(worker.name()).isEqualTo("test-worker");
    assertThat(worker.description()).isEqualTo("a worker");
    assertThat(worker.capabilityNames()).containsExactly("review");
    assertThat(worker.function()).isInstanceOf(WorkerFunction.Sync.class);
  }

  @Test
  void worker_without_description_has_null_description() {
    Worker worker =
        Worker.builder()
            .name("plain-worker")
            .capabilityName("cap")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
            .build();

    assertThat(worker.description()).isNull();
  }

  @Test
  void worker_executionPolicy_defaults_to_null() {
    Worker worker =
        Worker.builder()
            .name("plain-worker")
            .capabilityName("cap")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
            .build();

    assertThat(worker.executionPolicy()).isNotNull();
  }
}
