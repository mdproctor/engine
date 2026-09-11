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
package io.casehub.engine.common.internal.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.MilestoneSLAViolatedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MilestoneSLAOrchestratorTest {

  private static final UUID CASE_ID = UUID.randomUUID();
  private static final String MILESTONE_NAME = "orderDelivery";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private StubCaseInstanceCache caseCache;
  private StubCaseInstanceRepo caseRepo;
  private StubEventLogRepo eventLogRepo;
  private RecordingEventBus recordingEventBus;
  private MilestoneSLAOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    caseCache = new StubCaseInstanceCache();
    caseRepo = new StubCaseInstanceRepo();
    eventLogRepo = new StubEventLogRepo();
    recordingEventBus = new RecordingEventBus();

    orchestrator =
        new MilestoneSLAOrchestrator(caseCache, caseRepo, eventLogRepo, recordingEventBus.eventBus);
  }

  @Test
  void publishesViolationWhenMilestoneIsActive() {
    caseCache.instance = runningCase();
    eventLogRepo.events = List.of(milestoneEvent(CaseHubEventType.MILESTONE_ACTIVATED));

    orchestrator.execute(slaData());

    assertThat(recordingEventBus.publishedMessages).hasSize(1);
    var msg = recordingEventBus.publishedMessages.get(0);
    assertThat(msg.body).isInstanceOf(MilestoneSLAViolatedEvent.class);
  }

  @Test
  void skipsWhenMilestoneAlreadyCompleted() {
    caseCache.instance = runningCase();
    eventLogRepo.events = List.of(milestoneEvent(CaseHubEventType.MILESTONE_COMPLETED));

    orchestrator.execute(slaData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void skipsWhenCaseNotFound() {
    orchestrator.execute(slaData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void skipsWhenCaseIsTerminal() {
    caseCache.instance = caseWithStatus(CaseStatus.COMPLETED);

    orchestrator.execute(slaData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void skipsWhenCaseCancelled() {
    caseCache.instance = caseWithStatus(CaseStatus.CANCELLED);

    orchestrator.execute(slaData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void fallsBackToRepositoryWhenNotInCache() {
    caseRepo.instance = runningCase();
    eventLogRepo.events = List.of(milestoneEvent(CaseHubEventType.MILESTONE_ACTIVATED));

    orchestrator.execute(slaData());

    assertThat(recordingEventBus.publishedMessages).hasSize(1);
  }

  @Test
  void treatsNoEventsAsPending() {
    caseCache.instance = runningCase();
    eventLogRepo.events = List.of();

    orchestrator.execute(slaData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  // --- helpers ---

  private MilestoneSLAData slaData() {
    return new MilestoneSLAData(CASE_ID, MILESTONE_NAME);
  }

  private CaseInstance runningCase() {
    return caseWithStatus(CaseStatus.RUNNING);
  }

  private CaseInstance caseWithStatus(CaseStatus status) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(CASE_ID);
    instance.setState(status);
    return instance;
  }

  private EventLog milestoneEvent(CaseHubEventType eventType) {
    EventLog log = new EventLog();
    log.setCaseId(CASE_ID);
    log.setEventType(eventType);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setSeq(1L);
    log.setPayload(MAPPER.createObjectNode().put("milestoneName", MILESTONE_NAME));
    return log;
  }

  // --- test doubles ---

  static class StubCaseInstanceCache implements CaseInstanceCache {
    CaseInstance instance;

    @Override
    public CaseInstance get(UUID caseId) {
      return instance;
    }

    @Override
    public void put(CaseInstance caseInstance) {}

    @Override
    public void clear() {}

    @Override
    public List<CaseInstance> getAll() {
      return List.of();
    }
  }

  static class StubCaseInstanceRepo implements CrossTenantCaseInstanceRepository {
    CaseInstance instance;

    @Override
    public CaseInstance findByUuid(UUID uuid) {
      return instance;
    }
  }

  static class StubEventLogRepo implements CrossTenantEventLogRepository {
    List<EventLog> events = List.of();

    @Override
    public List<EventLog> findByTypes(java.util.Collection<CaseHubEventType> types) {
      return List.of();
    }

    @Override
    public List<EventLog> findByCaseAndTypes(
        UUID caseId, java.util.Collection<CaseHubEventType> types) {
      return events;
    }

    @Override
    public List<String> findSubmittedWorkWithoutCompletion() {
      return List.of();
    }

    @Override
    public List<EventLog> findByWorkerAndTypeAcrossTenants(String workerId, CaseHubEventType type) {
      return List.of();
    }

    @Override
    public EventLog findById(Long id) {
      return null;
    }

    @Override
    public List<EventLog> findByCaseAndWorkerAndType(
        UUID caseId, String workerId, CaseHubEventType type) {
      return List.of();
    }
  }

  record PublishedMessage(String address, Object body) {}

  static class RecordingEventBus {
    final List<PublishedMessage> publishedMessages = new ArrayList<>();
    final EventBus eventBus;

    RecordingEventBus() {
      this.eventBus =
          new EventBus(null) {
            @Override
            public io.vertx.mutiny.core.eventbus.EventBus publish(String address, Object body) {
              publishedMessages.add(new PublishedMessage(address, body));
              return this;
            }
          };
    }
  }
}
