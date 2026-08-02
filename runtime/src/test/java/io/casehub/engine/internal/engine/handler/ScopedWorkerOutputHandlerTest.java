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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.EventLogRepository;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScopedWorkerOutputHandlerTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ScopedWorkerOutputHandler handler;
  private ContextOutputApplier applier;
  private EventLogRepository eventLogRepository;
  private EventBus eventBus;
  private CaseInstance instance;

  @BeforeEach
  void setUp() {
    applier = mock(ContextOutputApplier.class);
    eventLogRepository = mock(EventLogRepository.class);
    eventBus = mock(EventBus.class);

    handler = new ScopedWorkerOutputHandler();
    handler.contextOutputApplier = applier;
    handler.eventLogRepository = eventLogRepository;
    handler.eventBus = eventBus;

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("ns");
    metaModel.setName("test");
    metaModel.setVersion("1.0");

    instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(metaModel);
    instance.setState(CaseStatus.RUNNING);
    instance.tenancyId = "tenant-1";
    instance.setCaseContext(new io.casehub.engine.internal.context.CaseContextImpl());
  }

  @Test
  void appliesOutputAndPublishesContextChanged() {
    ObjectNode diff = OBJECT_MAPPER.createObjectNode().put("key1", "value1");
    when(applier.apply(eq(instance), anyMap(), eq("binding1"))).thenReturn(diff);

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("key1", "value1"), "binding1", null);
    handler.onScopedWorkerOutput(event);

    verify(applier).apply(instance, Map.of("key1", "value1"), "binding1");

    ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository).append(logCaptor.capture(), eq("tenant-1"));
    EventLog log = logCaptor.getValue();
    assertEquals(CaseHubEventType.SCOPED_WORKER_OUTPUT, log.getEventType());
    assertEquals(EventStreamType.CASE, log.getStreamType());
    assertEquals("worker1", log.getWorkerId());

    verify(eventBus)
        .publish(eq(EventBusAddresses.CONTEXT_CHANGED), any(CaseContextChangedEvent.class));
  }

  @Test
  void emptyOutputFromApplier_noEventLogNoContextChanged() {
    when(applier.apply(any(), anyMap(), anyString())).thenReturn(null);

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("key1", "value1"), "binding1", null);
    handler.onScopedWorkerOutput(event);

    verify(eventLogRepository, never()).append(any(), anyString());
    verify(eventBus, never()).publish(anyString(), any());
  }

  @Test
  void terminalCaseState_completed_noOp() {
    instance.setState(CaseStatus.COMPLETED);

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("key1", "value1"), "binding1", null);
    handler.onScopedWorkerOutput(event);

    verify(applier, never()).apply(any(), anyMap(), anyString());
    verify(eventLogRepository, never()).append(any(), anyString());
    verify(eventBus, never()).publish(anyString(), any());
  }

  @Test
  void terminalCaseState_faulted_noOp() {
    instance.setState(CaseStatus.FAULTED);

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("key1", "value1"), "binding1", null);
    handler.onScopedWorkerOutput(event);

    verify(applier, never()).apply(any(), anyMap(), anyString());
  }

  @Test
  void terminalCaseState_cancelled_noOp() {
    instance.setState(CaseStatus.CANCELLED);

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("key1", "value1"), "binding1", null);
    handler.onScopedWorkerOutput(event);

    verify(applier, never()).apply(any(), anyMap(), anyString());
  }

  @Test
  void nullBindingName_stillApplies() {
    ObjectNode diff = OBJECT_MAPPER.createObjectNode().put("key1", "value1");
    when(applier.apply(eq(instance), anyMap(), isNull())).thenReturn(diff);

    var event =
        new ScopedWorkerOutputEvent(instance, "worker1", Map.of("key1", "value1"), null, null);
    handler.onScopedWorkerOutput(event);

    verify(applier).apply(instance, Map.of("key1", "value1"), null);
    verify(eventLogRepository).append(any(), eq("tenant-1"));
  }

  @Test
  void eventLogMetadata_containsBindingNameAndProducedKeys() {
    ObjectNode diff = OBJECT_MAPPER.createObjectNode().put("key1", "v1").put("key2", "v2");
    when(applier.apply(any(), anyMap(), anyString())).thenReturn(diff);

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("key1", "v1", "key2", "v2"), "binding1", null);
    handler.onScopedWorkerOutput(event);

    ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository).append(logCaptor.capture(), anyString());
    var metadata = logCaptor.getValue().getMetadata();
    assertEquals("binding1", metadata.get("bindingName").asText());
    assertTrue(metadata.has("contextChanges"));
    assertTrue(metadata.has("producedKeys"));
  }

  @Test
  void exceptionCaughtAndLogged_doesNotPropagate() {
    when(applier.apply(any(), anyMap(), anyString())).thenThrow(new RuntimeException("test error"));

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("key1", "value1"), "binding1", null);

    assertDoesNotThrow(() -> handler.onScopedWorkerOutput(event));
  }
}
