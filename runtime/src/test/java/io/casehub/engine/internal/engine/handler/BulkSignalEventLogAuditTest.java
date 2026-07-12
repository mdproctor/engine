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
package io.casehub.engine.internal.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.BulkSignalReceivedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Event;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class BulkSignalEventLogAuditTest {

  @Mock CaseInstanceCache caseInstanceCache;
  @Mock EventBus eventBus;
  @Mock ReactiveEventLogRepository reactiveEventLogRepository;
  @Mock WorkerExecutionRecoveryService recoveryService;
  @Mock Event<CaseLifecycleEvent> lifecycleEvents;
  @Mock LedgerTraceIdProvider traceIdProvider;
  @Mock Vertx vertx;

  private SignalReceivedEventHandler handler;

  private final UUID caseId = UUID.randomUUID();
  private final String tenancyId = "tenant-1";

  @BeforeEach
  void setUp() {
    handler =
        new SignalReceivedEventHandler(
            vertx,
            eventBus,
            caseInstanceCache,
            recoveryService,
            reactiveEventLogRepository,
            lifecycleEvents,
            traceIdProvider);
    when(traceIdProvider.currentTraceId()).thenReturn(Optional.empty());
  }

  @Test
  void bulkSignal_eventLog_containsUpdatedKeys() {
    CaseInstance instance = caseInstance();
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    when(reactiveEventLogRepository.append(any(EventLog.class), eq(tenancyId)))
        .thenReturn(Uni.createFrom().voidItem());

    io.vertx.mutiny.core.shareddata.SharedData sharedData =
        org.mockito.Mockito.mock(io.vertx.mutiny.core.shareddata.SharedData.class);
    io.vertx.mutiny.core.shareddata.Lock lock =
        org.mockito.Mockito.mock(io.vertx.mutiny.core.shareddata.Lock.class);
    when(vertx.sharedData()).thenReturn(sharedData);
    when(sharedData.getLocalLock(any())).thenReturn(Uni.createFrom().item(lock));
    when(lifecycleEvents.fireAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

    Map<String, Object> updates = Map.of("result", "done", "score", 42);
    BulkSignalReceivedEvent event = new BulkSignalReceivedEvent(caseId, tenancyId, updates);

    handler.onBulkSignalReceived(event).await().indefinitely();

    ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
    verify(reactiveEventLogRepository).append(captor.capture(), eq(tenancyId));

    EventLog captured = captor.getValue();
    JsonNode payload = captured.getPayload();
    assertThat(payload.get("type").asText()).isEqualTo("bulk_signal");
    assertThat(payload.has("updates"))
        .as("payload must include the updates map for audit")
        .isTrue();
    assertThat(payload.get("updates").has("result")).isTrue();
    assertThat(payload.get("updates").has("score")).isTrue();

    JsonNode metadata = captured.getMetadata();
    assertThat(metadata).as("metadata must contain updatedKeys").isNotNull();
    assertThat(metadata.has("updatedKeys")).isTrue();
  }

  private CaseInstance caseInstance() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = tenancyId;
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseContext(new io.casehub.engine.internal.context.CaseContextImpl());
    CaseMetaModel meta = new CaseMetaModel();
    meta.setName("test");
    meta.setNamespace("test");
    meta.setVersion("1.0");
    instance.setCaseMetaModel(meta);
    return instance;
  }
}
