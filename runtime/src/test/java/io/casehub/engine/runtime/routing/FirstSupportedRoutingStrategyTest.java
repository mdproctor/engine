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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirstSupportedRoutingStrategyTest {

  private FirstSupportedRoutingStrategy strategy;
  private Worker worker;
  private Capability capability;

  @BeforeEach
  void setUp() {
    strategy = new FirstSupportedRoutingStrategy();
    capability = Capability.of("test-cap", ".*", ".*");
    worker =
        Worker.builder()
            .name("test-worker")
            .capabilityName("test-cap")
            .function(input -> WorkerResult.of(Map.of()))
            .build();
  }

  @Test
  void selectsFirstSupportingBackend() {
    WorkerExecutionManager backend1 = mockBackend(false);
    WorkerExecutionManager backend2 = mockBackend(true);
    WorkerExecutionManager backend3 = mockBackend(true);

    Optional<WorkerExecutionManager> result =
        strategy.select(List.of(backend1, backend2, backend3), worker, capability, "tenant-1");

    assertThat(result).isPresent().containsSame(backend2);
  }

  @Test
  void returnsEmptyWhenNoBackendSupports() {
    WorkerExecutionManager backend1 = mockBackend(false);
    WorkerExecutionManager backend2 = mockBackend(false);

    Optional<WorkerExecutionManager> result =
        strategy.select(List.of(backend1, backend2), worker, capability, "tenant-1");

    assertThat(result).isEmpty();
  }

  @Test
  void returnsEmptyForEmptyCandidateList() {
    Optional<WorkerExecutionManager> result =
        strategy.select(List.of(), worker, capability, "tenant-1");

    assertThat(result).isEmpty();
  }

  @Test
  void respectsCandidateOrdering() {
    WorkerExecutionManager highPriority = mockBackend(true);
    WorkerExecutionManager lowPriority = mockBackend(true);

    Optional<WorkerExecutionManager> result =
        strategy.select(List.of(highPriority, lowPriority), worker, capability, "tenant-1");

    assertThat(result).isPresent().containsSame(highPriority);
  }

  @Test
  void rejectsBackendThatCannotExecuteFunction() {
    WorkerExecutionManager backend = mock(WorkerExecutionManager.class);
    when(backend.supports("test-cap", "tenant-1")).thenReturn(true);
    when(backend.canExecute(worker.function())).thenReturn(false);

    Optional<WorkerExecutionManager> result =
        strategy.select(List.of(backend), worker, capability, "tenant-1");

    assertThat(result).isEmpty();
  }

  @Test
  void skipsBackendThatCannotExecuteNone() {
    Worker noneWorker =
        Worker.builder().name("external").capabilityName("test-cap").noFunction().build();

    WorkerExecutionManager inProcess = mock(WorkerExecutionManager.class);
    when(inProcess.supports("test-cap", "tenant-1")).thenReturn(true);
    when(inProcess.canExecute(WorkerFunction.NONE)).thenReturn(false);

    WorkerExecutionManager external = mock(WorkerExecutionManager.class);
    when(external.supports("test-cap", "tenant-1")).thenReturn(true);
    when(external.canExecute(WorkerFunction.NONE)).thenReturn(true);

    Optional<WorkerExecutionManager> result =
        strategy.select(List.of(inProcess, external), noneWorker, capability, "tenant-1");

    assertThat(result).isPresent().containsSame(external);
  }

  private WorkerExecutionManager mockBackend(boolean supports) {
    WorkerExecutionManager mock = mock(WorkerExecutionManager.class);
    when(mock.supports("test-cap", "tenant-1")).thenReturn(supports);
    when(mock.canExecute(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    return mock;
  }
}
