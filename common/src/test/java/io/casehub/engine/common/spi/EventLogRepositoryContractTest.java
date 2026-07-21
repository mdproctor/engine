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
package io.casehub.engine.common.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.query.EventLogQuery;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public abstract class EventLogRepositoryContractTest {

  protected abstract EventLogRepository repository();

  protected abstract String tenancyId();

  private final UUID caseId = UUID.randomUUID();

  protected EventLog createEventLog(
      UUID caseId, CaseHubEventType eventType, EventStreamType streamType) {
    EventLog log = new EventLog();
    log.setCaseId(caseId);
    log.setEventType(eventType);
    log.setStreamType(streamType);
    log.setTimestamp(Instant.now());
    log.tenancyId = tenancyId();
    repository().append(log, tenancyId());
    return log;
  }

  @Test
  void queryByCaseId_returnsMatchingEvents() {
    createEventLog(caseId, CaseHubEventType.CASE_STARTED, EventStreamType.CASE);
    createEventLog(caseId, CaseHubEventType.SIGNAL_RECEIVED, EventStreamType.WORKER);
    createEventLog(UUID.randomUUID(), CaseHubEventType.CASE_STARTED, EventStreamType.CASE);

    var query = EventLogQuery.builder(caseId).build();
    assertThat(repository().query(query, tenancyId())).hasSize(2);
  }

  @Test
  void queryByEventType_filtersCorrectly() {
    createEventLog(caseId, CaseHubEventType.CASE_STARTED, EventStreamType.CASE);
    createEventLog(caseId, CaseHubEventType.SIGNAL_RECEIVED, EventStreamType.WORKER);

    var query =
        EventLogQuery.builder(caseId).eventTypes(Set.of(CaseHubEventType.CASE_STARTED)).build();
    var result = repository().query(query, tenancyId());
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getEventType()).isEqualTo(CaseHubEventType.CASE_STARTED);
  }

  @Test
  void queryByStreamType_filtersCorrectly() {
    createEventLog(caseId, CaseHubEventType.CASE_STARTED, EventStreamType.CASE);
    createEventLog(caseId, CaseHubEventType.SIGNAL_RECEIVED, EventStreamType.WORKER);

    var query = EventLogQuery.builder(caseId).streamTypes(Set.of(EventStreamType.WORKER)).build();
    var result = repository().query(query, tenancyId());
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getStreamType()).isEqualTo(EventStreamType.WORKER);
  }

  @Test
  void queryPagination_respectsPageAndSize() {
    for (int i = 0; i < 5; i++) {
      createEventLog(caseId, CaseHubEventType.SIGNAL_RECEIVED, EventStreamType.WORKER);
    }
    var page0 = EventLogQuery.builder(caseId).size(2).page(0).build();
    var page1 = EventLogQuery.builder(caseId).size(2).page(1).build();
    assertThat(repository().query(page0, tenancyId())).hasSize(2);
    assertThat(repository().query(page1, tenancyId())).hasSize(2);
  }

  @Test
  void count_returnsTotalForCase() {
    createEventLog(caseId, CaseHubEventType.CASE_STARTED, EventStreamType.CASE);
    createEventLog(caseId, CaseHubEventType.SIGNAL_RECEIVED, EventStreamType.WORKER);
    createEventLog(UUID.randomUUID(), CaseHubEventType.CASE_STARTED, EventStreamType.CASE);

    assertThat(repository().count(EventLogQuery.builder(caseId).build(), tenancyId())).isEqualTo(2);
  }

  @Test
  void count_respectsEventTypeFilter() {
    createEventLog(caseId, CaseHubEventType.CASE_STARTED, EventStreamType.CASE);
    createEventLog(caseId, CaseHubEventType.SIGNAL_RECEIVED, EventStreamType.WORKER);

    var query =
        EventLogQuery.builder(caseId).eventTypes(Set.of(CaseHubEventType.CASE_STARTED)).build();
    assertThat(repository().count(query, tenancyId())).isEqualTo(1);
  }
}
