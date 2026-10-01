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
package io.casehub.engine.persistence.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.recovery.CaseContextRecoveryStrategy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InMemoryCaseInstanceRepositorySnapshotTest {

  @Test
  void updateStateAndAppendEventCallsOnContextChanged() {
    InMemoryEventLogRepository eventLogRepo = new InMemoryEventLogRepository();
    InMemoryCaseInstanceRepository repo = new InMemoryCaseInstanceRepository(eventLogRepo);

    AtomicBoolean called = new AtomicBoolean(false);
    CaseContextRecoveryStrategy strategy =
        new CaseContextRecoveryStrategy() {
          @Override
          public CaseContext recover(CaseInstance instance) {
            return null;
          }

          @Override
          public void onContextChanged(CaseInstance instance, CaseContext context) {
            called.set(true);
          }
        };
    repo.setRecoveryStrategy(strategy);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseContext(org.mockito.Mockito.mock(CaseContext.class));
    repo.save(instance, "tenant-1");

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.CASE_STARTED);
    eventLog.setTimestamp(Instant.now());

    repo.updateStateAndAppendEvent(instance, eventLog, "tenant-1");

    assertTrue(called.get(), "onContextChanged should be called during updateStateAndAppendEvent");
  }

  @Test
  void onContextChangedNotCalledWhenNoStrategy() {
    InMemoryEventLogRepository eventLogRepo = new InMemoryEventLogRepository();
    InMemoryCaseInstanceRepository repo = new InMemoryCaseInstanceRepository(eventLogRepo);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    repo.save(instance, "tenant-1");

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.CASE_STARTED);
    eventLog.setTimestamp(Instant.now());

    assertDoesNotThrow(() -> repo.updateStateAndAppendEvent(instance, eventLog, "tenant-1"));
  }
}
