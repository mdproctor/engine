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

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public abstract class CaseInstanceRepositoryContractTest {

  protected abstract CaseInstanceRepository repository();

  protected abstract String tenancyId();

  protected CaseInstance createInstance(String namespace, String name, CaseStatus status) {
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace(namespace);
    meta.setName(name);
    meta.setVersion("1.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(meta);
    instance.setState(status);
    instance.tenancyId = tenancyId();
    return repository().save(instance, tenancyId());
  }

  @Test
  void queryAll_returnsAllForTenant() {
    createInstance("ns", "case-a", CaseStatus.RUNNING);
    createInstance("ns", "case-b", CaseStatus.COMPLETED);
    var result = repository().query(CaseInstanceQuery.all(), tenancyId());
    assertThat(result).hasSize(2);
  }

  @Test
  void queryByStatus_filtersCorrectly() {
    createInstance("ns", "case-a", CaseStatus.RUNNING);
    createInstance("ns", "case-b", CaseStatus.COMPLETED);
    var query = CaseInstanceQuery.builder().status(CaseStatus.RUNNING).build();
    var result = repository().query(query, tenancyId());
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getState()).isEqualTo(CaseStatus.RUNNING);
  }

  @Test
  void queryByNamespace_filtersCorrectly() {
    createInstance("ns1", "case-a", CaseStatus.RUNNING);
    createInstance("ns2", "case-b", CaseStatus.RUNNING);
    var query = CaseInstanceQuery.builder().namespace("ns1").build();
    assertThat(repository().query(query, tenancyId())).hasSize(1);
  }

  @Test
  void queryPagination_respectsPageAndSize() {
    for (int i = 0; i < 5; i++) {
      createInstance("ns", "case-" + i, CaseStatus.RUNNING);
    }
    var page0 = CaseInstanceQuery.builder().size(2).page(0).build();
    var page1 = CaseInstanceQuery.builder().size(2).page(1).build();
    assertThat(repository().query(page0, tenancyId())).hasSize(2);
    assertThat(repository().query(page1, tenancyId())).hasSize(2);
  }

  @Test
  void count_returnsTotal() {
    createInstance("ns", "case-a", CaseStatus.RUNNING);
    createInstance("ns", "case-b", CaseStatus.COMPLETED);
    assertThat(repository().count(CaseInstanceQuery.all(), tenancyId())).isEqualTo(2);
  }

  @Test
  void count_respectsStatusFilter() {
    createInstance("ns", "case-a", CaseStatus.RUNNING);
    createInstance("ns", "case-b", CaseStatus.COMPLETED);
    var query = CaseInstanceQuery.builder().status(CaseStatus.RUNNING).build();
    assertThat(repository().count(query, tenancyId())).isEqualTo(1);
  }

  @Test
  void actorId_roundTrip_preservesValue() {
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("ns");
    meta.setName("case-a");
    meta.setVersion("1.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(meta);
    instance.setState(CaseStatus.RUNNING);
    instance.setActorId("alice");
    instance.tenancyId = tenancyId();

    CaseInstance saved = repository().save(instance, tenancyId());
    assertThat(saved.getActorId()).isEqualTo("alice");

    CaseInstance found = repository().findByUuid(saved.getUuid(), tenancyId());
    assertThat(found).isNotNull();
    assertThat(found.getActorId()).isEqualTo("alice");
  }

  @Test
  void actorId_null_whenNotSet() {
    CaseInstance instance = createInstance("ns", "case-b", CaseStatus.RUNNING);
    CaseInstance found = repository().findByUuid(instance.getUuid(), tenancyId());
    assertThat(found).isNotNull();
    assertThat(found.getActorId()).isNull();
  }

  @Test
  void createdAt_roundTrip_preservesValue() {
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("ns");
    meta.setName("case-a");
    meta.setVersion("1.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(meta);
    instance.setState(CaseStatus.RUNNING);
    instance.setCreatedAt(Instant.parse("2026-06-15T10:30:00Z"));
    instance.tenancyId = tenancyId();

    CaseInstance saved = repository().save(instance, tenancyId());
    assertThat(saved.getCreatedAt()).isEqualTo(Instant.parse("2026-06-15T10:30:00Z"));

    CaseInstance found = repository().findByUuid(saved.getUuid(), tenancyId());
    assertThat(found).isNotNull();
    assertThat(found.getCreatedAt()).isEqualTo(Instant.parse("2026-06-15T10:30:00Z"));
  }
}
