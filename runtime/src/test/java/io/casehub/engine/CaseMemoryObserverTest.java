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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.memory.CaseMemoryObserver;
import io.casehub.memory.runtime.MemoryEmitter;
import io.casehub.neocortex.memory.MemoryInput;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseMemoryObserverTest {

  private MemoryEmitter emitter;
  private CaseMemoryObserver observer;

  @BeforeEach
  void setUp() {
    emitter = mock(MemoryEmitter.class);
    observer = new CaseMemoryObserver(emitter);
  }

  @Test
  void caseCompleted_storesMemory() {
    final UUID caseId = UUID.randomUUID();

    observer.onCaseLifecycleEvent(
        CaseLifecycleEvent.of(
            caseId, null, "CloseCase", "CaseCompleted", "COMPLETED", null, null, null));

    verify(emitter, times(1)).emit(any(MemoryInput.class));
  }

  @Test
  void caseCompleted_memoryContainsCaseId() {
    final UUID caseId = UUID.randomUUID();
    final MemoryInput[] captured = new MemoryInput[1];
    doAnswer(
            inv -> {
              captured[0] = inv.getArgument(0);
              return null;
            })
        .when(emitter)
        .emit(any());

    observer.onCaseLifecycleEvent(
        CaseLifecycleEvent.of(
            caseId, null, "CloseCase", "CaseCompleted", "COMPLETED", null, null, null));

    assertThat(captured[0]).isNotNull();
    assertThat(captured[0].caseId()).isEqualTo(caseId.toString());
    assertThat(captured[0].entityId()).isEqualTo(caseId.toString());
    assertThat(captured[0].text()).contains("CaseCompleted");
  }

  @Test
  void caseStarted_doesNotStoreMemory() {
    final UUID caseId = UUID.randomUUID();

    observer.onCaseLifecycleEvent(
        CaseLifecycleEvent.of(
            caseId, null, "StartCase", "CaseStarted", "RUNNING", null, null, null));

    verify(emitter, never()).emit(any());
  }
}
