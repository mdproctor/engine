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
import io.casehub.engine.common.internal.model.CaseInstance;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tenant isolation contract test for {@link InMemoryReactiveCaseInstanceRepository}. Verifies that
 * tenant A's data is never visible to tenant B.
 */
class InMemoryReactiveCaseInstanceRepositoryTenancyTest {

  private InMemoryReactiveCaseInstanceRepository repository;
  private InMemoryReactiveEventLogRepository eventLogRepository;

  @BeforeEach
  void setUp() {
    InMemoryEventLogRepository blockingEventLogRepo = new InMemoryEventLogRepository();
    eventLogRepository = new InMemoryReactiveEventLogRepository();
    eventLogRepository.setDelegate(blockingEventLogRepo);
    InMemoryCaseInstanceRepository blockingRepo = new InMemoryCaseInstanceRepository();
    blockingRepo.setEventLogRepository(blockingEventLogRepo);
    repository = new InMemoryReactiveCaseInstanceRepository();
    repository.setDelegate(blockingRepo);
  }

  @Test
  void save_and_findByUuid_sameTenant_found() {
    String tenantA = "tenant-a-" + UUID.randomUUID();
    CaseInstance instance = buildInstance();

    CaseInstance saved = repository.save(instance, tenantA).await().atMost(Duration.ofSeconds(5));

    CaseInstance found =
        repository.findByUuid(saved.getUuid(), tenantA).await().atMost(Duration.ofSeconds(5));

    assertThat(found).isNotNull();
    assertThat(found.tenancyId).isEqualTo(tenantA);
    assertThat(found.getUuid()).isEqualTo(saved.getUuid());
  }

  @Test
  void findByUuid_differentTenant_returnsNull() {
    String tenantA = "tenant-a-" + UUID.randomUUID();
    String tenantB = "tenant-b-" + UUID.randomUUID();

    CaseInstance saved =
        repository.save(buildInstance(), tenantA).await().atMost(Duration.ofSeconds(5));

    CaseInstance found =
        repository.findByUuid(saved.getUuid(), tenantB).await().atMost(Duration.ofSeconds(5));

    assertThat(found).isNull();
  }

  @Test
  void update_wrongTenant_throws() {
    String tenantA = "tenant-a-" + UUID.randomUUID();
    String tenantB = "tenant-b-" + UUID.randomUUID();

    CaseInstance saved =
        repository.save(buildInstance(), tenantA).await().atMost(Duration.ofSeconds(5));

    // update with wrong tenant must throw — the in-memory implementation throws
    // IllegalStateException
    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class,
        () -> repository.update(saved, tenantB).await().atMost(Duration.ofSeconds(5)));
  }

  @Test
  void twoCases_differentTenants_isolatedFromEachOther() {
    String tenantA = "tenant-a-" + UUID.randomUUID();
    String tenantB = "tenant-b-" + UUID.randomUUID();

    CaseInstance caseA = buildInstance();
    CaseInstance caseB = buildInstance();

    repository.save(caseA, tenantA).await().atMost(Duration.ofSeconds(5));
    repository.save(caseB, tenantB).await().atMost(Duration.ofSeconds(5));

    // A cannot see B's case
    assertThat(
            repository.findByUuid(caseB.getUuid(), tenantA).await().atMost(Duration.ofSeconds(5)))
        .isNull();

    // B cannot see A's case
    assertThat(
            repository.findByUuid(caseA.getUuid(), tenantB).await().atMost(Duration.ofSeconds(5)))
        .isNull();

    // Each can see their own
    assertThat(
            repository.findByUuid(caseA.getUuid(), tenantA).await().atMost(Duration.ofSeconds(5)))
        .isNotNull();
    assertThat(
            repository.findByUuid(caseB.getUuid(), tenantB).await().atMost(Duration.ofSeconds(5)))
        .isNotNull();
  }

  private CaseInstance buildInstance() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    return instance;
  }
}
