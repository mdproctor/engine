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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.Participation;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScopedWorkerOutputHandlerTest {

  private ScopedWorkerOutputHandler handler;
  private ScopedWorkerRegistry registry;
  private CaseDefinitionRegistry definitionRegistry;
  private EventBus eventBus;
  private CaseInstance caseInstance;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new ScopedWorkerRegistry();
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    eventBus = mock(EventBus.class);

    handler = new ScopedWorkerOutputHandler();
    handler.definitionRegistry = definitionRegistry;
    handler.scopedWorkerRegistry = registry;
    handler.eventBus = eventBus;

    caseId = UUID.randomUUID();
    caseInstance = new CaseInstance();
    caseInstance.setUuid(caseId);
    caseInstance.setCaseContext(new io.casehub.engine.internal.context.CaseContextImpl());
    CaseMetaModel meta = new CaseMetaModel();
    caseInstance.setCaseMetaModel(meta);
  }

  @Test
  void applies_output_to_case_context_when_session_exists() {
    registry.register(
        new ScopedWorkerRegistry.ScopeKey(caseId, "binding-a"),
        new ScopedWorkerSession.Reinvoked(
            "binding-a",
            caseId,
            "worker-1",
            LifecycleScope.COMPOUND,
            Participation.PARTICIPANT,
            new AtomicReference<>(Map.of()),
            new AtomicReference<>(null)));

    CaseDefinition def = mock(CaseDefinition.class);
    when(def.getBindings()).thenReturn(List.of());
    when(definitionRegistry.getCaseDefinition(any())).thenReturn(def);

    var event =
        new ScopedWorkerOutputEvent(
            caseInstance, "binding-a", Map.of("result", "processed"), ExecutionMode.REINVOKED);

    handler.onScopedWorkerOutput(event);

    assertThat(caseInstance.getCaseContext().get("result")).isEqualTo("processed");
    verify(eventBus).publish(any(String.class), any());
  }

  @Test
  void discards_output_when_session_terminated() {
    CaseDefinition def = mock(CaseDefinition.class);
    when(definitionRegistry.getCaseDefinition(any())).thenReturn(def);

    var event =
        new ScopedWorkerOutputEvent(
            caseInstance, "gone-binding", Map.of("result", "late"), ExecutionMode.REINVOKED);

    handler.onScopedWorkerOutput(event);

    assertThat(caseInstance.getCaseContext().get("result")).isNull();
    verify(eventBus, never()).publish(any(String.class), any());
  }
}
