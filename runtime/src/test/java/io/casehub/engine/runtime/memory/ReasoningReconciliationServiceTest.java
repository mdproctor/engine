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
package io.casehub.engine.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReasoningReconciliationServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CrossTenantEventLogRepository eventLogRepo;
  private CaseMemoryStore store;
  private ReasoningReconciliationService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    eventLogRepo = mock(CrossTenantEventLogRepository.class);
    store = mock(CaseMemoryStore.class);
    Instance<CaseMemoryStore> storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(store);
    when(store.store(any())).thenReturn("mem-1");

    service = new ReasoningReconciliationService();
    service.eventLogRepository = eventLogRepo;
    service.caseMemoryStore = storeInstance;
    service.enabled = true;
    service.batchSize = 100;
  }

  @Test
  void reconcile_storesReasoningFromEventLog() {
    EventLog entry = createEventLog(1L, "worker-1", "Approved because X, Y, Z");
    when(eventLogRepo.findByTypesAfterId(any(), any(long.class))).thenReturn(List.of(entry));

    service.reconcile();

    ArgumentCaptor<MemoryInput> captor = ArgumentCaptor.forClass(MemoryInput.class);
    verify(store).store(captor.capture());
    MemoryInput stored = captor.getValue();
    assertThat(stored.text()).isEqualTo("Approved because X, Y, Z");
    assertThat(stored.domain().name()).isEqualTo("worker-reasoning");
    assertThat(stored.attributes()).containsEntry("workerName", "worker-1");
    assertThat(stored.attributes()).containsEntry("reconciled", "true");
    assertThat(service.getLastProcessedId()).isEqualTo(1L);
  }

  @Test
  void reconcile_skipsEntriesWithoutReasoning() {
    EventLog entry = createEventLog(2L, "worker-1", null);
    when(eventLogRepo.findByTypesAfterId(any(), any(long.class))).thenReturn(List.of(entry));

    service.reconcile();

    verify(store, never()).store(any());
    assertThat(service.getLastProcessedId()).isEqualTo(2L);
  }

  @Test
  void reconcile_advancesWatermark() {
    EventLog e1 = createEventLog(5L, "w1", "reasoning-1");
    EventLog e2 = createEventLog(10L, "w2", "reasoning-2");
    when(eventLogRepo.findByTypesAfterId(any(), any(long.class))).thenReturn(List.of(e1, e2));

    service.reconcile();

    verify(store, times(2)).store(any());
    assertThat(service.getLastProcessedId()).isEqualTo(10L);

    when(eventLogRepo.findByTypesAfterId(any(), any(long.class))).thenReturn(List.of());
    service.reconcile();
    assertThat(service.getLastProcessedId()).isEqualTo(10L);
  }

  @Test
  void reconcile_respectsBatchSize() {
    service.batchSize = 1;
    EventLog e1 = createEventLog(1L, "w1", "r1");
    EventLog e2 = createEventLog(2L, "w2", "r2");
    when(eventLogRepo.findByTypesAfterId(any(), any(long.class))).thenReturn(List.of(e1, e2));

    service.reconcile();

    verify(store, times(1)).store(any());
  }

  @Test
  void reconcile_disabledSkipsSilently() {
    service.enabled = false;
    service.reconcile();
    verify(store, never()).store(any());
  }

  @Test
  void reconcile_storeFailureDoesNotBlockOthers() {
    EventLog e1 = createEventLog(1L, "w1", "r1");
    EventLog e2 = createEventLog(2L, "w2", "r2");
    when(eventLogRepo.findByTypesAfterId(any(), any(long.class))).thenReturn(List.of(e1, e2));
    when(store.store(any())).thenThrow(new RuntimeException("store down")).thenReturn("mem-2");

    service.reconcile();

    verify(store, times(2)).store(any());
    assertThat(service.getLastProcessedId()).isEqualTo(2L);
  }

  @Test
  void reconcile_failedEventLogType_setsCorrectOutcome() {
    EventLog entry = createFailedEventLog(3L, "worker-1", "Failed analysis reasoning");
    when(eventLogRepo.findByTypesAfterId(any(), any(long.class))).thenReturn(List.of(entry));

    service.reconcile();

    ArgumentCaptor<MemoryInput> captor = ArgumentCaptor.forClass(MemoryInput.class);
    verify(store).store(captor.capture());
    assertThat(captor.getValue().attributes()).containsEntry("outcome", "REROUTE");
  }

  private EventLog createEventLog(Long id, String workerId, String reasoning) {
    EventLog entry = new EventLog();
    entry.id = id;
    entry.tenancyId = "tenant-1";
    entry.setCaseId(UUID.randomUUID());
    entry.setWorkerId(workerId);
    entry.setEventType(CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    entry.setTimestamp(Instant.now());
    ObjectNode metadata = MAPPER.createObjectNode();
    metadata.put("inputDataHash", "hash-1");
    if (reasoning != null) {
      metadata.put("reasoning", reasoning);
    }
    entry.setMetadata(metadata);
    return entry;
  }

  private EventLog createFailedEventLog(Long id, String workerId, String reasoning) {
    EventLog entry = new EventLog();
    entry.id = id;
    entry.tenancyId = "tenant-1";
    entry.setCaseId(UUID.randomUUID());
    entry.setWorkerId(workerId);
    entry.setEventType(CaseHubEventType.WORKER_EXECUTION_FAILED);
    entry.setTimestamp(Instant.now());
    ObjectNode metadata = MAPPER.createObjectNode();
    metadata.put("inputDataHash", "hash-1");
    metadata.put("disposition", "REROUTE");
    if (reasoning != null) {
      metadata.put("reasoning", reasoning);
    }
    entry.setMetadata(metadata);
    return entry;
  }
}
