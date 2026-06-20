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
package io.casehub.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryCaseInstanceRepositoryTest {

  InMemoryCaseInstanceRepository repository;
  CaseMetaModel meta;

  @BeforeEach
  void setUp() {
    repository = new InMemoryCaseInstanceRepository();
    meta = new CaseMetaModel();
    meta.setName("test-case");
    meta.setNamespace("test-ns");
    meta.setVersion("1.0");
    meta.setId(1L);
  }

  // --- Happy path ---

  @Test
  void save_populatesId() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);

    CaseInstance saved = repository.save(instance, "test-tenant").await().indefinitely();

    assertThat(saved.id).isNotNull().isPositive();
  }

  @Test
  void save_returnsSameInstance() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);

    CaseInstance saved = repository.save(instance, "test-tenant").await().indefinitely();

    assertThat(saved).isSameAs(instance);
  }

  @Test
  void findByUuid_returnsSavedInstance() {
    UUID uuid = UUID.randomUUID();
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    instance.setUuid(uuid);
    repository.save(instance, "test-tenant").await().indefinitely();

    CaseInstance found = repository.findByUuid(uuid, "test-tenant").await().indefinitely();

    assertThat(found).isNotNull();
    assertThat(found.getUuid()).isEqualTo(uuid);
    assertThat(found.getState()).isEqualTo(CaseStatus.RUNNING);
    assertThat(found.getCaseMetaModel()).isNotNull();
    assertThat(found.getCaseMetaModel().getId()).isEqualTo(meta.getId());
  }

  @Test
  void update_changesState() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    repository.save(instance, "test-tenant").await().indefinitely();

    instance.setState(CaseStatus.COMPLETED);
    repository.update(instance, "test-tenant").await().indefinitely();

    CaseInstance reloaded =
        repository.findByUuid(instance.getUuid(), "test-tenant").await().indefinitely();
    assertThat(reloaded.getState()).isEqualTo(CaseStatus.COMPLETED);
  }

  @Test
  void update_returnsSameInstance() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    repository.save(instance, "test-tenant").await().indefinitely();

    CaseInstance result = repository.update(instance, "test-tenant").await().indefinitely();

    assertThat(result).isSameAs(instance);
  }

  @Test
  void idsAreUniqueAcrossSaves() {
    CaseInstance a = newInstance(CaseStatus.RUNNING);
    CaseInstance b = newInstance(CaseStatus.RUNNING);

    repository.save(a, "test-tenant").await().indefinitely();
    repository.save(b, "test-tenant").await().indefinitely();

    assertThat(a.id).isNotEqualTo(b.id);
  }

  @Test
  void save_doesNotReassignIdIfAlreadySet() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    repository.save(instance, "test-tenant").await().indefinitely();
    Long firstId = instance.id;

    repository.save(instance, "test-tenant").await().indefinitely();

    assertThat(instance.id).isEqualTo(firstId);
  }

  // --- Edge cases ---

  @Test
  void findByUuid_returnsNullForUnknown() {
    CaseInstance result =
        repository.findByUuid(UUID.randomUUID(), "test-tenant").await().indefinitely();
    assertThat(result).isNull();
  }

  @Test
  void update_afterMultipleStateChanges_reflectsLatest() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    repository.save(instance, "test-tenant").await().indefinitely();

    instance.setState(CaseStatus.WAITING);
    repository.update(instance, "test-tenant").await().indefinitely();

    instance.setState(CaseStatus.COMPLETED);
    repository.update(instance, "test-tenant").await().indefinitely();

    CaseInstance reloaded =
        repository.findByUuid(instance.getUuid(), "test-tenant").await().indefinitely();
    assertThat(reloaded.getState()).isEqualTo(CaseStatus.COMPLETED);
  }

  @Test
  void update_throwsForUnknownUuid() {
    CaseInstance instance = newInstance(CaseStatus.RUNNING);
    // deliberately not saved

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.update(instance, "test-tenant").await().indefinitely())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void updateStateAndAppendEvent_updatesInstanceAndAppendsEvent() {
    InMemoryEventLogRepository eventLogRepo = new InMemoryEventLogRepository();
    InMemoryCaseInstanceRepository repo = new InMemoryCaseInstanceRepository();
    repo.setEventLogRepository(eventLogRepo);

    CaseMetaModel model = new CaseMetaModel();
    model.setNamespace("ns");
    model.setName("n");
    model.setVersion("1");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(model);
    instance.setState(CaseStatus.RUNNING);
    repo.save(instance, "test-tenant").subscribe().asCompletionStage().toCompletableFuture().join();

    instance.setState(CaseStatus.COMPLETED);
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.CASE_COMPLETED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());

    repo.updateStateAndAppendEvent(instance, eventLog, "test-tenant")
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    CaseInstance updated =
        repo.findByUuid(instance.getUuid(), "test-tenant")
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();
    assertThat(updated.getState()).isEqualTo(CaseStatus.COMPLETED);
    assertThat(eventLog.id).isNotNull();
    assertThat(eventLog.getSeq()).isNotNull();
    EventLog found =
        eventLogRepo
            .findById(eventLog.id, "test-tenant")
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();
    assertThat(found).isNotNull();
    assertThat(found.getEventType()).isEqualTo(CaseHubEventType.CASE_COMPLETED);
  }

  // --- Query methods ---

  @Test
  void findByStatus_returnsMatchingInstances() {
    repository.save(newInstance(CaseStatus.RUNNING), "test-tenant").await().indefinitely();
    repository.save(newInstance(CaseStatus.RUNNING), "test-tenant").await().indefinitely();
    repository.save(newInstance(CaseStatus.COMPLETED), "test-tenant").await().indefinitely();

    List<CaseInstance> running =
        repository.findByStatus(CaseStatus.RUNNING, "test-tenant").await().indefinitely();

    assertThat(running).hasSize(2);
    assertThat(running).allMatch(ci -> ci.getState() == CaseStatus.RUNNING);
  }

  @Test
  void findByStatus_respectsTenancy() {
    repository.save(newInstance(CaseStatus.RUNNING), "tenant-a").await().indefinitely();
    repository.save(newInstance(CaseStatus.RUNNING), "tenant-b").await().indefinitely();

    List<CaseInstance> result =
        repository.findByStatus(CaseStatus.RUNNING, "tenant-a").await().indefinitely();

    assertThat(result).hasSize(1);
  }

  @Test
  void findByStatus_returnsEmptyForNoMatch() {
    repository.save(newInstance(CaseStatus.RUNNING), "test-tenant").await().indefinitely();

    List<CaseInstance> result =
        repository.findByStatus(CaseStatus.COMPLETED, "test-tenant").await().indefinitely();

    assertThat(result).isEmpty();
  }

  @Test
  void findAll_returnsAllForTenant() {
    repository.save(newInstance(CaseStatus.RUNNING), "test-tenant").await().indefinitely();
    repository.save(newInstance(CaseStatus.COMPLETED), "test-tenant").await().indefinitely();
    repository.save(newInstance(CaseStatus.RUNNING), "other-tenant").await().indefinitely();

    List<CaseInstance> result = repository.findAll("test-tenant").await().indefinitely();

    assertThat(result).hasSize(2);
  }

  @Test
  void findAll_returnsEmptyForUnknownTenant() {
    repository.save(newInstance(CaseStatus.RUNNING), "test-tenant").await().indefinitely();

    List<CaseInstance> result = repository.findAll("unknown-tenant").await().indefinitely();

    assertThat(result).isEmpty();
  }

  @Test
  void findByNamespaceAndName_returnsMatchingInstances() {
    CaseMetaModel otherMeta = new CaseMetaModel();
    otherMeta.setName("other-case");
    otherMeta.setNamespace("other-ns");
    otherMeta.setVersion("1.0");
    otherMeta.setId(2L);

    CaseInstance matchA = newInstance(CaseStatus.RUNNING);
    CaseInstance matchB = newInstance(CaseStatus.COMPLETED);
    CaseInstance noMatch = new CaseInstance();
    noMatch.setUuid(UUID.randomUUID());
    noMatch.setState(CaseStatus.RUNNING);
    noMatch.setCaseMetaModel(otherMeta);

    repository.save(matchA, "test-tenant").await().indefinitely();
    repository.save(matchB, "test-tenant").await().indefinitely();
    repository.save(noMatch, "test-tenant").await().indefinitely();

    List<CaseInstance> result =
        repository
            .findByNamespaceAndName("test-ns", "test-case", "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(2);
    assertThat(result)
        .allMatch(
            ci ->
                ci.getCaseMetaModel().getNamespace().equals("test-ns")
                    && ci.getCaseMetaModel().getName().equals("test-case"));
  }

  @Test
  void findByNamespaceAndName_respectsTenancy() {
    repository.save(newInstance(CaseStatus.RUNNING), "tenant-a").await().indefinitely();
    repository.save(newInstance(CaseStatus.RUNNING), "tenant-b").await().indefinitely();

    List<CaseInstance> result =
        repository
            .findByNamespaceAndName("test-ns", "test-case", "tenant-a")
            .await()
            .indefinitely();

    assertThat(result).hasSize(1);
  }

  // --- Helper ---

  private CaseInstance newInstance(CaseStatus status) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(status);
    instance.setCaseMetaModel(meta);
    return instance;
  }
}
