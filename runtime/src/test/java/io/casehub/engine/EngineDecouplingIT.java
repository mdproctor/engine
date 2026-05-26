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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the EventLogRepository SPI is functional end-to-end regardless of the active
 * persistence implementation (hibernate or memory).
 */
@QuarkusTest
class EngineDecouplingIT {

  @Inject EventLogRepository eventLogRepository;

  @Test
  void eventLogRepository_appendAndFind_happyPath() {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(UUID.randomUUID());
    eventLog.setEventType(CaseHubEventType.CASE_STARTED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());

    eventLogRepository
        .append(eventLog)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(eventLog.id).isNotNull().isPositive();
    assertThat(eventLog.getSeq()).isNotNull().isPositive();

    EventLog found =
        eventLogRepository
            .findById(eventLog.id)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();
    assertThat(found).isNotNull();
    assertThat(found.getEventType()).isEqualTo(CaseHubEventType.CASE_STARTED);
  }

  @Test
  void eventLogRepository_findByTypes_returnsMatchingEvents() {
    UUID caseId = UUID.randomUUID();

    EventLog started = new EventLog();
    started.setCaseId(caseId);
    started.setEventType(CaseHubEventType.CASE_STARTED);
    started.setStreamType(EventStreamType.CASE);
    started.setTimestamp(Instant.now());
    eventLogRepository.append(started).subscribe().asCompletionStage().toCompletableFuture().join();

    EventLog completed = new EventLog();
    completed.setCaseId(caseId);
    completed.setEventType(CaseHubEventType.CASE_COMPLETED);
    completed.setStreamType(EventStreamType.CASE);
    completed.setTimestamp(Instant.now());
    eventLogRepository
        .append(completed)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    List<EventLog> found =
        eventLogRepository
            .findByTypes(List.of(CaseHubEventType.CASE_STARTED, CaseHubEventType.CASE_COMPLETED))
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    assertThat(found).hasSizeGreaterThanOrEqualTo(2);
    assertThat(found).anyMatch(e -> e.getEventType() == CaseHubEventType.CASE_STARTED);
    assertThat(found).anyMatch(e -> e.getEventType() == CaseHubEventType.CASE_COMPLETED);
  }
}
