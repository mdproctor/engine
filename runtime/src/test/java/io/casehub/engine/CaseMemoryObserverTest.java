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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.memory.CaseMemoryObserver;
import io.casehub.platform.api.memory.CaseMemoryStore;
import io.casehub.platform.api.memory.MemoryInput;
import jakarta.enterprise.inject.Instance;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseMemoryObserverTest {

  private CaseMemoryStore memoryStore;
  private CaseMemoryObserver observer;

  @BeforeEach
  void setUp() {
    memoryStore = mock(CaseMemoryStore.class);
    when(memoryStore.store(any())).thenReturn("mem-id-1");

    @SuppressWarnings("unchecked")
    final Instance<CaseMemoryStore> instance = mock(Instance.class);
    when(instance.isResolvable()).thenReturn(true);
    when(instance.get()).thenReturn(memoryStore);

    observer = new CaseMemoryObserver(instance);
  }

  @Test
  void caseCompleted_storesMemory() {
    final UUID caseId = UUID.randomUUID();

    observer.onCaseLifecycleEvent(
        new CaseLifecycleEvent(
            caseId, "CloseCase", "CaseCompleted", "COMPLETED", null, null, null));

    verify(memoryStore, times(1)).store(any(MemoryInput.class));
  }

  @Test
  void caseCompleted_memoryContainsCaseId() {
    final UUID caseId = UUID.randomUUID();
    final MemoryInput[] captured = new MemoryInput[1];
    when(memoryStore.store(any()))
        .thenAnswer(
            inv -> {
              captured[0] = inv.getArgument(0);
              return "id";
            });

    observer.onCaseLifecycleEvent(
        new CaseLifecycleEvent(
            caseId, "CloseCase", "CaseCompleted", "COMPLETED", null, null, null));

    assertThat(captured[0]).isNotNull();
    assertThat(captured[0].caseId()).isEqualTo(caseId.toString());
    assertThat(captured[0].entityId()).isEqualTo(caseId.toString());
    assertThat(captured[0].text()).contains("CaseCompleted");
  }

  @Test
  void caseStarted_doesNotStoreMemory() {
    final UUID caseId = UUID.randomUUID();

    observer.onCaseLifecycleEvent(
        new CaseLifecycleEvent(caseId, "StartCase", "CaseStarted", "RUNNING", null, null, null));

    verify(memoryStore, never()).store(any());
  }

  @Test
  void storeNotResolvable_doesNotThrow() {
    @SuppressWarnings("unchecked")
    final Instance<CaseMemoryStore> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    final CaseMemoryObserver noStoreObserver = new CaseMemoryObserver(absent);

    noStoreObserver.onCaseLifecycleEvent(
        new CaseLifecycleEvent(
            UUID.randomUUID(), "CloseCase", "CaseCompleted", "COMPLETED", null, null, null));

    verify(absent, never()).get();
  }
}
