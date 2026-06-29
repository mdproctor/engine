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
package io.casehub.engine.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionRoutingStrategy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompositeWorkerExecutionManagerTest {

  private CompositeWorkerExecutionManager composite;
  private WorkerExecutionRoutingStrategy strategy;
  private WorkerExecutionManager backend1;
  private WorkerExecutionManager backend2;
  private Worker worker;
  private Capability capability;
  private CaseInstance instance;

  @BeforeEach
  void setUp() {
    strategy = mock(WorkerExecutionRoutingStrategy.class);
    backend1 = mock(WorkerExecutionManager.class);
    backend2 = mock(WorkerExecutionManager.class);

    capability = Capability.of("test-cap", "{}", "{}");
    worker =
        Worker.builder()
            .name("test-worker")
            .capability(capability)
            .function(input -> WorkerResult.of(Map.of()))
            .build();

    instance = new CaseInstance();
    instance.tenancyId = "tenant-1";
    instance.setUuid(UUID.randomUUID());

    composite = new CompositeWorkerExecutionManager(strategy, List.of(backend1, backend2));
  }

  @Test
  void submit_routesToSelectedBackend() {
    when(strategy.select(any(), any(), any(), anyString())).thenReturn(Optional.of(backend2));
    when(backend2.submit(any(), any(), any(), any(), any()))
        .thenReturn(Uni.createFrom().voidItem());

    composite
        .submit(1L, instance, worker, capability, Map.of())
        .subscribe()
        .withSubscriber(UniAssertSubscriber.create())
        .assertCompleted();

    verify(backend2).submit(1L, instance, worker, capability, Map.of());
  }

  @Test
  void submit_throwsProvisioningExceptionWhenNoBackendSelected() {
    when(strategy.select(any(), any(), any(), anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                composite.submit(1L, instance, worker, capability, Map.of()).await().indefinitely())
        .isInstanceOf(io.casehub.api.spi.ProvisioningException.class);
  }

  @Test
  void submit_throwsProvisioningExceptionWhenNoBackendsDiscovered() {
    CompositeWorkerExecutionManager empty =
        new CompositeWorkerExecutionManager(strategy, List.of());

    assertThatThrownBy(
            () -> empty.submit(1L, instance, worker, capability, Map.of()).await().indefinitely())
        .isInstanceOf(io.casehub.api.spi.ProvisioningException.class);
  }

  @Test
  void getActiveWorkCount_sumsAcrossBackends() {
    when(backend1.getActiveWorkCount("w1")).thenReturn(3);
    when(backend2.getActiveWorkCount("w1")).thenReturn(5);

    assertThat(composite.getActiveWorkCount("w1")).isEqualTo(8);
  }

  @Test
  void getActiveCaseIds_unionsAcrossBackends() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    when(backend1.getActiveCaseIds("w1")).thenReturn(List.of(id1));
    when(backend2.getActiveCaseIds("w1")).thenReturn(List.of(id2));

    assertThat(composite.getActiveCaseIds("w1")).containsExactlyInAnyOrder(id1, id2);
  }

  @Test
  void supports_returnsTrueIfAnyBackendSupports() {
    when(backend1.supports("cap-a", "t1")).thenReturn(false);
    when(backend2.supports("cap-a", "t1")).thenReturn(true);

    assertThat(composite.supports("cap-a", "t1")).isTrue();
  }

  @Test
  void supports_returnsFalseWhenNoneSupport() {
    when(backend1.supports("cap-a", "t1")).thenReturn(false);
    when(backend2.supports("cap-a", "t1")).thenReturn(false);

    assertThat(composite.supports("cap-a", "t1")).isFalse();
  }

  @Test
  void canExecute_delegatesToBackends_returnsTrueWhenAnyCanExecute() {
    when(backend1.canExecute(any())).thenReturn(false);
    when(backend2.canExecute(any())).thenReturn(true);

    assertThat(composite.canExecute(WorkerFunction.NONE)).isTrue();
  }

  @Test
  void canExecute_returnsFalseWhenNoBackendCanExecute() {
    when(backend1.canExecute(any())).thenReturn(false);
    when(backend2.canExecute(any())).thenReturn(false);

    assertThat(composite.canExecute(WorkerFunction.NONE)).isFalse();
  }

  @Test
  void schedulePersistedEvent_routesToSupportingBackend() {
    EventLog eventLog = new EventLog();
    eventLog.tenancyId = "t1";
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode metadata = mapper.createObjectNode();
    metadata.put("capabilityName", "test-cap");
    eventLog.setMetadata(metadata);

    when(backend1.supports("test-cap", "t1")).thenReturn(false);
    when(backend2.supports("test-cap", "t1")).thenReturn(true);
    when(backend2.schedulePersistedEvent(eventLog)).thenReturn(Uni.createFrom().voidItem());

    composite
        .schedulePersistedEvent(eventLog)
        .subscribe()
        .withSubscriber(UniAssertSubscriber.create())
        .assertCompleted();

    verify(backend2).schedulePersistedEvent(eventLog);
  }
}
