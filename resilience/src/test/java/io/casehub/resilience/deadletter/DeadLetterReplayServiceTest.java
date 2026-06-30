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
package io.casehub.resilience.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeadLetterReplayServiceTest {

  private DeadLetterQueue queue;
  private CrossTenantEventLogRepository eventLogRepository;
  private CrossTenantCaseInstanceRepository caseInstanceRepository;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private EventBus eventBus;
  private DeadLetterReplayService service;

  @BeforeEach
  void setup() {
    queue = new DeadLetterQueue();
    eventLogRepository = mock(CrossTenantEventLogRepository.class);
    caseInstanceRepository = mock(CrossTenantCaseInstanceRepository.class);
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    eventBus = mock(EventBus.class);
    service =
        new DeadLetterReplayService(
            queue, eventLogRepository, caseInstanceRepository, caseDefinitionRegistry, eventBus);
  }

  @Test
  void replay_unknownId_returnsEmpty() {
    assertThat(service.replay("no-such-id")).isEmpty();
  }

  @Test
  void replay_alreadyReplayed_returnsEmpty() {
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w", "h", Map.of());
    queue.markReplayed(entry.deadLetterId());
    assertThat(service.replay(entry.deadLetterId())).isEmpty();
  }

  @Test
  void replay_discarded_returnsEmpty() {
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w", "h", Map.of());
    queue.discard(entry.deadLetterId());
    assertThat(service.replay(entry.deadLetterId())).isEmpty();
  }

  @Test
  void replay_eventLogNotFound_leavesEntryPendingReturnsEmpty() {
    UUID caseId = UUID.randomUUID();
    DeadLetterEntry entry = queue.add(caseId, "worker-a", "hash-x", Map.of());
    when(eventLogRepository.findByCaseAndWorkerAndType(
            caseId, "worker-a", CaseHubEventType.WORKER_SCHEDULED))
        .thenReturn(Uni.createFrom().item(List.of()));

    Optional<DeadLetterEntry> result = service.replay(entry.deadLetterId());

    assertThat(result).isEmpty();
    assertThat(entry.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
  }

  @Test
  void replay_faultedCase_returnsEmpty() {
    UUID caseId = UUID.randomUUID();
    DeadLetterEntry entry = queue.add(caseId, "worker-b", "hash-y", Map.of());

    EventLog scheduledLog = scheduledLog(caseId, "worker-b", "hash-y");
    when(eventLogRepository.findByCaseAndWorkerAndType(
            caseId, "worker-b", CaseHubEventType.WORKER_SCHEDULED))
        .thenReturn(Uni.createFrom().item(List.of(scheduledLog)));

    CaseInstance faulted = new CaseInstance();
    faulted.setState(CaseStatus.FAULTED);
    when(caseInstanceRepository.findByUuid(caseId)).thenReturn(Uni.createFrom().item(faulted));

    assertThat(service.replay(entry.deadLetterId())).isEmpty();
    assertThat(entry.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
  }

  @Test
  void replay_success_publishesEventAndMarksReplayed() {
    UUID caseId = UUID.randomUUID();
    String workerId = "worker-c";
    String hash = "hash-z";
    DeadLetterEntry entry = queue.add(caseId, workerId, hash, Map.of());

    EventLog scheduledLog = scheduledLog(caseId, workerId, hash);
    when(eventLogRepository.findByCaseAndWorkerAndType(
            caseId, workerId, CaseHubEventType.WORKER_SCHEDULED))
        .thenReturn(Uni.createFrom().item(List.of(scheduledLog)));

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("TestCase");
    metaModel.setVersion("1.0.0");

    CaseInstance running = new CaseInstance();
    running.setState(CaseStatus.RUNNING);
    running.setCaseMetaModel(metaModel);
    when(caseInstanceRepository.findByUuid(caseId)).thenReturn(Uni.createFrom().item(running));

    Capability cap =
        Capability.builder().name(workerId).inputSchema("{}").outputSchema("{}").build();
    Worker worker =
        Worker.builder()
            .name(workerId)
            .capabilityName(workerId)
            .function(new WorkerFunction.Sync(i -> WorkerResult.of(Map.of())))
            .build();
    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("TestCase")
            .version("1.0.0")
            .capabilities(cap)
            .workers(worker)
            .bindings(
                Binding.builder()
                    .name("b")
                    .capability(cap)
                    .on(new ContextChangeTrigger(".x"))
                    .build())
            .build();
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
    when(eventBus.publish(eq(EventBusAddresses.WORKER_SCHEDULE), any())).thenReturn(null);

    Optional<DeadLetterEntry> result = service.replay(entry.deadLetterId());

    assertThat(result).isPresent();
    assertThat(entry.status()).isEqualTo(DeadLetterStatus.REPLAYED);
    assertThat(entry.replayAttempts()).isEqualTo(1);
    verify(eventBus).publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
  }

  private static EventLog scheduledLog(UUID caseId, String workerId, String hash) {
    EventLog e = new EventLog();
    e.setCaseId(caseId);
    e.setWorkerId(workerId);
    e.setEventType(CaseHubEventType.WORKER_SCHEDULED);
    e.setStreamType(EventStreamType.CASE);
    e.setTimestamp(Instant.now());
    com.fasterxml.jackson.databind.node.ObjectNode meta =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    meta.put("inputDataHash", hash);
    e.setMetadata(meta);
    return e;
  }
}
