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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.RetryState;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.SignalSettlementTracker;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkerRetriesExhaustedEventHandlerTest {

  @Mock CaseInstanceCache caseInstanceCache;
  @Mock EventBus eventBus;
  @Mock CaseInstanceRepository caseInstanceRepository;
  @Mock WorkerStatusListener workerStatusListener;
  @Mock SignalSettlementTracker settlementTracker;

  private WorkerRetriesExhaustedEventHandler handler;
  private final UUID caseId = UUID.randomUUID();
  private final String tenancyId = "tenant-1";

  @BeforeEach
  void setUp() {
    handler =
        new WorkerRetriesExhaustedEventHandler(
            caseInstanceCache,
            eventBus,
            caseInstanceRepository,
            workerStatusListener,
            settlementTracker);
  }

  @Test
  void onExhausted_withSignalId_callsRecordCompletion() {
    UUID signalId = UUID.randomUUID();
    CaseInstance instance = caseInstance();
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    // caseInstanceRepository.updateStateAndAppendEvent is void — no stub needed

    handler.onWorkerRetriesExhaustedEvent(
        new WorkerRetriesExhaustedEvent(
            caseId, tenancyId, "worker-a", "hash", null, signalId, RetryState.empty()));

    verify(settlementTracker).recordCompletion(signalId);
  }

  @Test
  void onExhausted_withoutSignalId_doesNotCallRecordCompletion() {
    CaseInstance instance = caseInstance();
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    // caseInstanceRepository.updateStateAndAppendEvent is void — no stub needed

    handler.onWorkerRetriesExhaustedEvent(
        new WorkerRetriesExhaustedEvent(
            caseId, tenancyId, "worker-a", "hash", null, null, RetryState.empty()));

    verify(settlementTracker, never()).recordCompletion(any());
  }

  private CaseInstance caseInstance() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = tenancyId;
    instance.setState(CaseStatus.RUNNING);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setName("test");
    meta.setNamespace("test");
    meta.setVersion("1.0");
    instance.setCaseMetaModel(meta);
    return instance;
  }
}
