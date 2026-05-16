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
package io.casehub.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.internal.model.CaseMetaModel;
import io.casehub.engine.internal.model.SubCaseGroup;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.casehub.engine.spi.CaseMetaModelRepository;
import io.casehub.engine.spi.EventLogRepository;
import io.casehub.engine.spi.SubCaseGroupRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PersistenceIntegrationTest {

  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject EventLogRepository eventLogRepository;
  @Inject SubCaseGroupRepository subCaseGroupRepository;

  private CaseMetaModel savedMeta;
  private UUID parentCaseId;

  @BeforeEach
  void setUp() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setName("integration-test-" + unique);
    meta.setNamespace("test-ns");
    meta.setVersion("1.0");
    savedMeta = run(() -> metaModelRepository.save(meta));
    parentCaseId = UUID.randomUUID();
  }

  private <T> T run(Supplier<Uni<T>> supplier) {
    try {
      return VertxContextSupport.subscribeAndAwait(supplier);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  // ========== CaseInstance + EventLog Integration ==========

  @Test
  void updateStateAndAppendEvent_atomicallyUpdatesInstanceAndCreatesEvent() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance));

    instance.setState(CaseStatus.COMPLETED);
    EventLog eventLog = newEventLog(instance.getUuid(), CaseHubEventType.CASE_COMPLETED);

    run(() -> instanceRepository.updateStateAndAppendEvent(instance, eventLog));

    // Verify instance state updated
    CaseInstance reloaded = run(() -> instanceRepository.findByUuid(instance.getUuid()));
    assertThat(reloaded.getState()).isEqualTo(CaseStatus.COMPLETED);

    // Verify event created
    assertThat(eventLog.id).isNotNull();
    assertThat(eventLog.getSeq()).isNotNull();

    EventLog foundEvent = run(() -> eventLogRepository.findById(eventLog.id));
    assertThat(foundEvent).isNotNull();
    assertThat(foundEvent.getEventType()).isEqualTo(CaseHubEventType.CASE_COMPLETED);
    assertThat(foundEvent.getCaseId()).isEqualTo(instance.getUuid());
  }

  @Test
  void updateStateAndAppendEvent_multipleEvents_sequencesAreOrdered() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance));

    // Append 3 events
    EventLog event1 = newEventLog(instance.getUuid(), CaseHubEventType.CASE_STARTED);
    instance.setState(CaseStatus.RUNNING);
    run(() -> instanceRepository.updateStateAndAppendEvent(instance, event1));

    EventLog event2 = newEventLog(instance.getUuid(), CaseHubEventType.CASE_STATUS_CHANGED);
    instance.setState(CaseStatus.SUSPENDED);
    run(() -> instanceRepository.updateStateAndAppendEvent(instance, event2));

    EventLog event3 = newEventLog(instance.getUuid(), CaseHubEventType.CASE_COMPLETED);
    instance.setState(CaseStatus.COMPLETED);
    run(() -> instanceRepository.updateStateAndAppendEvent(instance, event3));

    // Verify sequences are strictly increasing
    assertThat(event1.getSeq()).isNotNull();
    assertThat(event2.getSeq()).isNotNull();
    assertThat(event3.getSeq()).isNotNull();
    assertThat(event1.getSeq()).isLessThan(event2.getSeq());
    assertThat(event2.getSeq()).isLessThan(event3.getSeq());

    // Verify all events persisted
    List<EventLog> events =
        run(() -> eventLogRepository.findByCaseWithFilters(instance.getUuid(), null, null));
    assertThat(events).hasSize(3);
    assertThat(events.stream().map(EventLog::getSeq))
        .containsExactly(event1.getSeq(), event2.getSeq(), event3.getSeq());
  }

  // ========== CaseInstance + CaseMetaModel Integration ==========

  @Test
  void caseInstance_loadsMetaModel() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance));

    CaseInstance found = run(() -> instanceRepository.findByUuid(instance.getUuid()));

    assertThat(found.getCaseMetaModel()).isNotNull();
    assertThat(found.getCaseMetaModel().getId()).isEqualTo(savedMeta.getId());
    assertThat(found.getCaseMetaModel().getName()).isEqualTo(savedMeta.getName());
    assertThat(found.getCaseMetaModel().getNamespace()).isEqualTo(savedMeta.getNamespace());
  }

  // ========== SubCaseGroup + CaseInstance Integration ==========

  @Test
  void subCaseGroup_registerChild_childCaseExists() {
    CaseInstance childInstance = newInstance(CaseStatus.RUNNING);
    childInstance.setParentCaseId(parentCaseId);
    run(() -> instanceRepository.save(childInstance));

    String groupId = "group-1";
    run(
        () ->
            subCaseGroupRepository.getOrCreate(
                parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));

    SubCaseGroup group =
        run(
            () ->
                subCaseGroupRepository.registerChild(
                    parentCaseId, groupId, childInstance.getUuid()));

    assertThat(group.getChildCaseIds()).containsExactly(childInstance.getUuid());

    // Verify child can be found by its UUID
    CaseInstance foundChild = run(() -> instanceRepository.findByUuid(childInstance.getUuid()));
    assertThat(foundChild).isNotNull();
    assertThat(foundChild.getParentCaseId()).isEqualTo(parentCaseId);
  }

  @Test
  void subCaseGroup_findByChildCaseId_returnsCorrectGroup() {
    UUID childCaseId = UUID.randomUUID();
    String groupId = "group-1";

    SubCaseGroup group =
        run(
            () ->
                subCaseGroupRepository.getOrCreate(
                    parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));
    run(() -> subCaseGroupRepository.registerChild(parentCaseId, groupId, childCaseId));

    var foundGroup = run(() -> subCaseGroupRepository.findByChildCaseId(childCaseId));

    assertThat(foundGroup).isPresent();
    assertThat(foundGroup.get().getGroupId()).isEqualTo(groupId);
    assertThat(foundGroup.get().getParentCaseId()).isEqualTo(parentCaseId);
    assertThat(foundGroup.get().getChildCaseIds()).containsExactly(childCaseId);
  }

  @Test
  void subCaseGroup_multipleChildren_allRegistered() {
    String groupId = "group-1";
    run(
        () ->
            subCaseGroupRepository.getOrCreate(
                parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));

    UUID child1 = UUID.randomUUID();
    UUID child2 = UUID.randomUUID();
    UUID child3 = UUID.randomUUID();

    run(() -> subCaseGroupRepository.registerChild(parentCaseId, groupId, child1));
    run(() -> subCaseGroupRepository.registerChild(parentCaseId, groupId, child2));
    SubCaseGroup group =
        run(() -> subCaseGroupRepository.registerChild(parentCaseId, groupId, child3));

    assertThat(group.getChildCaseIds()).containsExactlyInAnyOrder(child1, child2, child3);
  }

  @Test
  void subCaseGroup_incrementCompleted_reflectsInGroup() {
    String groupId = "group-1";
    run(
        () ->
            subCaseGroupRepository.getOrCreate(
                parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));

    SubCaseGroup group =
        run(() -> subCaseGroupRepository.incrementCompleted(parentCaseId, groupId));
    assertThat(group.getCompletedCount()).isEqualTo(1);

    group = run(() -> subCaseGroupRepository.incrementCompleted(parentCaseId, groupId));
    assertThat(group.getCompletedCount()).isEqualTo(2);
  }

  @Test
  void subCaseGroup_incrementRejected_reflectsInGroup() {
    String groupId = "group-1";
    run(
        () ->
            subCaseGroupRepository.getOrCreate(
                parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));

    SubCaseGroup group = run(() -> subCaseGroupRepository.incrementRejected(parentCaseId, groupId));
    assertThat(group.getRejectedCount()).isEqualTo(1);
  }

  @Test
  void subCaseGroup_markPolicyTriggered_canOnlyTriggerOnce() {
    String groupId = "group-1";
    run(
        () ->
            subCaseGroupRepository.getOrCreate(
                parentCaseId, groupId, 3, 2, OnThresholdReached.KEEP));

    boolean firstMark =
        run(() -> subCaseGroupRepository.markPolicyTriggered(parentCaseId, groupId));
    assertThat(firstMark).isTrue();

    boolean secondMark =
        run(() -> subCaseGroupRepository.markPolicyTriggered(parentCaseId, groupId));
    assertThat(secondMark).isFalse();
  }

  // ========== Cross-Repository Consistency ==========

  @Test
  void fullCaseLifecycle_parentAndChildCases_withEventsAndGroups() {
    // Create parent case
    CaseInstance parentInstance = newInstance(CaseStatus.RUNNING);
    run(() -> instanceRepository.save(parentInstance));

    // Append event to parent
    EventLog parentEvent = newEventLog(parentInstance.getUuid(), CaseHubEventType.CASE_STARTED);
    run(() -> instanceRepository.updateStateAndAppendEvent(parentInstance, parentEvent));

    // Create SubCaseGroup
    String groupId = "child-group";
    run(
        () ->
            subCaseGroupRepository.getOrCreate(
                parentInstance.getUuid(), groupId, 2, 2, OnThresholdReached.CANCEL));

    // Create child cases
    CaseInstance child1 = newInstance(CaseStatus.RUNNING);
    child1.setParentCaseId(parentInstance.getUuid());
    run(() -> instanceRepository.save(child1));
    run(
        () ->
            subCaseGroupRepository.registerChild(
                parentInstance.getUuid(), groupId, child1.getUuid()));

    CaseInstance child2 = newInstance(CaseStatus.RUNNING);
    child2.setParentCaseId(parentInstance.getUuid());
    run(() -> instanceRepository.save(child2));
    run(
        () ->
            subCaseGroupRepository.registerChild(
                parentInstance.getUuid(), groupId, child2.getUuid()));

    // Complete child1
    child1.setState(CaseStatus.COMPLETED);
    EventLog child1Event = newEventLog(child1.getUuid(), CaseHubEventType.CASE_COMPLETED);
    run(() -> instanceRepository.updateStateAndAppendEvent(child1, child1Event));
    run(() -> subCaseGroupRepository.incrementCompleted(parentInstance.getUuid(), groupId));

    // Complete child2
    child2.setState(CaseStatus.COMPLETED);
    EventLog child2Event = newEventLog(child2.getUuid(), CaseHubEventType.CASE_COMPLETED);
    run(() -> instanceRepository.updateStateAndAppendEvent(child2, child2Event));
    SubCaseGroup group =
        run(() -> subCaseGroupRepository.incrementCompleted(parentInstance.getUuid(), groupId));

    // Verify final state
    assertThat(group.getCompletedCount()).isEqualTo(2);
    assertThat(group.getChildCaseIds())
        .containsExactlyInAnyOrder(child1.getUuid(), child2.getUuid());

    CaseInstance foundParent = run(() -> instanceRepository.findByUuid(parentInstance.getUuid()));
    assertThat(foundParent.getState()).isEqualTo(CaseStatus.RUNNING);

    CaseInstance foundChild1 = run(() -> instanceRepository.findByUuid(child1.getUuid()));
    assertThat(foundChild1.getState()).isEqualTo(CaseStatus.COMPLETED);
    assertThat(foundChild1.getParentCaseId()).isEqualTo(parentInstance.getUuid());

    List<EventLog> allEvents =
        run(() -> eventLogRepository.findByCaseWithFilters(child1.getUuid(), null, null));
    assertThat(allEvents).hasSize(1);
    assertThat(allEvents.get(0).getEventType()).isEqualTo(CaseHubEventType.CASE_COMPLETED);
  }

  // ========== Event Log Stream Ordering ==========

  @Test
  void eventLog_multipleCases_sequencesAreGloballyUnique() {
    CaseInstance instance1 = newInstance(CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance1));

    CaseInstance instance2 = newInstance(CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance2));

    EventLog event1 = newEventLog(instance1.getUuid(), CaseHubEventType.CASE_STARTED);
    instance1.setState(CaseStatus.RUNNING);
    run(() -> instanceRepository.updateStateAndAppendEvent(instance1, event1));

    EventLog event2 = newEventLog(instance2.getUuid(), CaseHubEventType.CASE_STARTED);
    instance2.setState(CaseStatus.RUNNING);
    run(() -> instanceRepository.updateStateAndAppendEvent(instance2, event2));

    EventLog event3 = newEventLog(instance1.getUuid(), CaseHubEventType.CASE_COMPLETED);
    instance1.setState(CaseStatus.COMPLETED);
    run(() -> instanceRepository.updateStateAndAppendEvent(instance1, event3));

    // All sequences should be unique across cases
    assertThat(event1.getSeq()).isNotEqualTo(event2.getSeq());
    assertThat(event1.getSeq()).isNotEqualTo(event3.getSeq());
    assertThat(event2.getSeq()).isNotEqualTo(event3.getSeq());

    // Sequences should be strictly increasing globally
    List<Long> allSeqs = List.of(event1.getSeq(), event2.getSeq(), event3.getSeq());
    assertThat(allSeqs).doesNotHaveDuplicates();
  }

  // ========== Helper Methods ==========

  private CaseInstance newInstance(CaseStatus status) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(status);
    instance.setCaseMetaModel(savedMeta);
    return instance;
  }

  private EventLog newEventLog(UUID caseId, CaseHubEventType eventType) {
    EventLog log = new EventLog();
    log.setCaseId(caseId);
    log.setEventType(eventType);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now().truncatedTo(ChronoUnit.MICROS));
    return log;
  }
}
