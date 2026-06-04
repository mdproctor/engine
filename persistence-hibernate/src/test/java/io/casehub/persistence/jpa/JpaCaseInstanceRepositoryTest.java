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
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@QuarkusTest
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JpaCaseInstanceRepositoryTest {

  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject EventLogRepository eventLogRepository;

  private CaseMetaModel savedMeta;

  @BeforeEach
  void setUp() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setName("instance-test-" + unique);
    meta.setNamespace("test-ns");
    meta.setVersion("1.0");
    savedMeta = run(() -> metaModelRepository.save(meta, "test-tenant"));
  }

  @Test
  void save_populatesId() {
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);

    CaseInstance saved = run(() -> instanceRepository.save(instance, "test-tenant"));

    assertThat(saved.id).isNotNull().isPositive();
  }

  @Test
  void findByUuid_returnsNullForUnknown() {
    CaseInstance result =
        run(() -> instanceRepository.findByUuid(UUID.randomUUID(), "test-tenant"));

    assertThat(result).isNull();
  }

  @Test
  void findByUuid_returnsSavedInstanceWithMetaModel() {
    UUID uuid = UUID.randomUUID();
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    instance.setUuid(uuid);
    run(() -> instanceRepository.save(instance, "test-tenant"));

    CaseInstance found = run(() -> instanceRepository.findByUuid(uuid, "test-tenant"));

    assertThat(found).isNotNull();
    assertThat(found.getUuid()).isEqualTo(uuid);
    assertThat(found.getState()).isEqualTo(CaseStatus.RUNNING);
    assertThat(found.getCaseMetaModel()).isNotNull();
    assertThat(found.getCaseMetaModel().getId()).isEqualTo(savedMeta.getId());
  }

  @Test
  void updateStateAndAppendEvent_atomicallyUpdatesAndPersistsEvent() {
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance, "test-tenant"));

    instance.setState(CaseStatus.FAULTED);
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.CASE_FAULTED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(
        java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));

    run(() -> instanceRepository.updateStateAndAppendEvent(instance, eventLog, "test-tenant"));

    CaseInstance updated =
        run(() -> instanceRepository.findByUuid(instance.getUuid(), "test-tenant"));
    assertThat(updated.getState()).isEqualTo(CaseStatus.FAULTED);
    assertThat(eventLog.id).isNotNull();
    assertThat(eventLog.getSeq()).isNotNull();

    EventLog found = run(() -> eventLogRepository.findById(eventLog.id, "test-tenant"));
    assertThat(found).isNotNull();
    assertThat(found.getEventType()).isEqualTo(CaseHubEventType.CASE_FAULTED);
  }

  @Test
  void update_changesState() {
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance, "test-tenant"));

    instance.setState(CaseStatus.COMPLETED);
    run(() -> instanceRepository.update(instance, "test-tenant"));

    CaseInstance reloaded =
        run(() -> instanceRepository.findByUuid(instance.getUuid(), "test-tenant"));
    assertThat(reloaded.getState()).isEqualTo(CaseStatus.COMPLETED);
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

  private CaseInstance newInstance(CaseMetaModel meta, CaseStatus status) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(status);
    instance.setCaseMetaModel(meta);
    return instance;
  }

  // ========== Edge Case Tests ==========

  @Test
  void save_handlesNullParentCaseId() {
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    instance.setParentCaseId(null); // NULL parent is valid for root cases

    CaseInstance saved = run(() -> instanceRepository.save(instance, "test-tenant"));

    assertThat(saved.id).isNotNull();
    assertThat(saved.getParentCaseId()).isNull();
  }

  @Test
  void save_handlesNonNullParentCaseId() {
    UUID parentCaseId = UUID.randomUUID();
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    instance.setParentCaseId(parentCaseId);

    CaseInstance saved = run(() -> instanceRepository.save(instance, "test-tenant"));

    assertThat(saved.id).isNotNull();
    assertThat(saved.getParentCaseId()).isEqualTo(parentCaseId);
  }

  @Test
  void update_preservesParentCaseId() {
    UUID parentCaseId = UUID.randomUUID();
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    instance.setParentCaseId(parentCaseId);
    run(() -> instanceRepository.save(instance, "test-tenant"));

    instance.setState(CaseStatus.COMPLETED);
    run(() -> instanceRepository.update(instance, "test-tenant"));

    CaseInstance reloaded =
        run(() -> instanceRepository.findByUuid(instance.getUuid(), "test-tenant"));
    assertThat(reloaded.getState()).isEqualTo(CaseStatus.COMPLETED);
    assertThat(reloaded.getParentCaseId()).isEqualTo(parentCaseId);
  }

  @Test
  void update_canChangeMultipleFields() {
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance, "test-tenant"));

    UUID newParentCaseId = UUID.randomUUID();
    instance.setState(CaseStatus.FAULTED);
    instance.setParentCaseId(newParentCaseId);
    run(() -> instanceRepository.update(instance, "test-tenant"));

    CaseInstance reloaded =
        run(() -> instanceRepository.findByUuid(instance.getUuid(), "test-tenant"));
    assertThat(reloaded.getState()).isEqualTo(CaseStatus.FAULTED);
    assertThat(reloaded.getParentCaseId()).isEqualTo(newParentCaseId);
  }

  @Test
  void findByUuid_returnsInstanceWithAllFields() {
    UUID parentCaseId = UUID.randomUUID();
    UUID instanceUuid = UUID.randomUUID();
    CaseInstance instance = newInstance(savedMeta, CaseStatus.SUSPENDED);
    instance.setUuid(instanceUuid);
    instance.setParentCaseId(parentCaseId);
    run(() -> instanceRepository.save(instance, "test-tenant"));

    CaseInstance found = run(() -> instanceRepository.findByUuid(instanceUuid, "test-tenant"));

    assertThat(found).isNotNull();
    assertThat(found.getUuid()).isEqualTo(instanceUuid);
    assertThat(found.getState()).isEqualTo(CaseStatus.SUSPENDED);
    assertThat(found.getParentCaseId()).isEqualTo(parentCaseId);
    assertThat(found.getCaseMetaModel()).isNotNull();
    assertThat(found.getCaseMetaModel().getId()).isEqualTo(savedMeta.getId());
  }

  @Test
  void updateStateAndAppendEvent_bothOperationsSucceed() {
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance, "test-tenant"));

    instance.setState(CaseStatus.COMPLETED);
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.CASE_COMPLETED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(
        java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));

    run(() -> instanceRepository.updateStateAndAppendEvent(instance, eventLog, "test-tenant"));

    // Verify both operations persisted
    CaseInstance updated =
        run(() -> instanceRepository.findByUuid(instance.getUuid(), "test-tenant"));
    assertThat(updated.getState()).isEqualTo(CaseStatus.COMPLETED);

    EventLog foundEvent = run(() -> eventLogRepository.findById(eventLog.id, "test-tenant"));
    assertThat(foundEvent).isNotNull();
    assertThat(foundEvent.getEventType()).isEqualTo(CaseHubEventType.CASE_COMPLETED);
  }

  @Test
  void save_withAllStates() {
    for (CaseStatus status : CaseStatus.values()) {
      String unique = UUID.randomUUID().toString().substring(0, 8);
      CaseMetaModel meta = new CaseMetaModel();
      meta.setName("state-test-" + unique);
      meta.setNamespace("test-ns");
      meta.setVersion("1.0");
      CaseMetaModel savedMetaForStatus = run(() -> metaModelRepository.save(meta, "test-tenant"));

      CaseInstance instance = newInstance(savedMetaForStatus, status);
      CaseInstance saved = run(() -> instanceRepository.save(instance, "test-tenant"));

      assertThat(saved.getState()).isEqualTo(status);
    }
  }

  @Test
  void findByUuid_concurrentReads() throws InterruptedException {
    CaseInstance instance = newInstance(savedMeta, CaseStatus.RUNNING);
    run(() -> instanceRepository.save(instance, "test-tenant"));
    final UUID uuid = instance.getUuid();

    int threadCount = 5;
    List<CaseInstance> results = new java.util.concurrent.CopyOnWriteArrayList<>();
    List<Thread> threads = new java.util.ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      Thread t =
          new Thread(
              () -> {
                CaseInstance found = run(() -> instanceRepository.findByUuid(uuid, "test-tenant"));
                results.add(found);
              });
      threads.add(t);
      t.start();
    }

    for (Thread t : threads) {
      t.join();
    }

    assertThat(results).hasSize(threadCount);
    assertThat(results).allMatch(r -> r != null && r.getUuid().equals(uuid));
  }
}
