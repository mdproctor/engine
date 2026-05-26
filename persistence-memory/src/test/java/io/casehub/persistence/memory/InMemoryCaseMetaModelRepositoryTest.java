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

import io.casehub.engine.common.internal.model.CaseMetaModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryCaseMetaModelRepositoryTest {

  InMemoryCaseMetaModelRepository repository;

  @BeforeEach
  void setUp() {
    repository = new InMemoryCaseMetaModelRepository();
  }

  // --- Happy path ---

  @Test
  void save_populatesIdAndCreatedAt() {
    CaseMetaModel meta = metaModel("save-populates", "ns", "1.0");

    CaseMetaModel saved = repository.save(meta).await().indefinitely();

    assertThat(saved.getId()).isNotNull().isPositive();
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void save_returnsSameInstance() {
    CaseMetaModel meta = metaModel("same-instance", "ns", "1.0");

    CaseMetaModel saved = repository.save(meta).await().indefinitely();

    assertThat(saved).isSameAs(meta);
  }

  @Test
  void findByKey_returnsRegisteredMetaModel() {
    CaseMetaModel meta = metaModel("find-by-key", "repo-ns", "2.0");
    repository.save(meta).await().indefinitely();

    CaseMetaModel found =
        repository.findByKey("repo-ns", "find-by-key", "2.0").await().indefinitely();

    assertThat(found).isNotNull();
    assertThat(found.getName()).isEqualTo("find-by-key");
    assertThat(found.getNamespace()).isEqualTo("repo-ns");
    assertThat(found.getVersion()).isEqualTo("2.0");
    assertThat(found.getId()).isEqualTo(meta.getId());
  }

  @Test
  void save_thenFindByKey_roundTrip() {
    CaseMetaModel meta = metaModel("round-trip", "rt-ns", "3.0");
    meta.setTitle("Round Trip Title");
    meta.setDsl("yaml");

    CaseMetaModel saved = repository.save(meta).await().indefinitely();
    CaseMetaModel found = repository.findByKey("rt-ns", "round-trip", "3.0").await().indefinitely();

    assertThat(found.getId()).isEqualTo(saved.getId());
    assertThat(found.getTitle()).isEqualTo("Round Trip Title");
    assertThat(found.getDsl()).isEqualTo("yaml");
    assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
  }

  @Test
  void idsAreUniqueAcrossSaves() {
    CaseMetaModel a = metaModel("a", "ns", "1.0");
    CaseMetaModel b = metaModel("b", "ns", "1.0");

    repository.save(a).await().indefinitely();
    repository.save(b).await().indefinitely();

    assertThat(a.getId()).isNotEqualTo(b.getId());
  }

  @Test
  void save_doesNotReassignIdIfAlreadySet() {
    CaseMetaModel meta = metaModel("idempotent", "ns", "1.0");
    repository.save(meta).await().indefinitely();
    Long firstId = meta.getId();

    repository.save(meta).await().indefinitely();

    assertThat(meta.getId()).isEqualTo(firstId);
  }

  // --- Edge cases ---

  @Test
  void findByKey_returnsNullForUnknown() {
    CaseMetaModel result = repository.findByKey("no-ns", "no-name", "9.9").await().indefinitely();
    assertThat(result).isNull();
  }

  @Test
  void findByKey_isKeyExact_namespaceVersionMustMatch() {
    CaseMetaModel meta = metaModel("exact", "exact-ns", "1.0");
    repository.save(meta).await().indefinitely();

    assertThat(repository.findByKey("wrong-ns", "exact", "1.0").await().indefinitely()).isNull();
    assertThat(repository.findByKey("exact-ns", "exact", "2.0").await().indefinitely()).isNull();
  }

  // --- Helper ---

  private CaseMetaModel metaModel(String name, String namespace, String version) {
    CaseMetaModel m = new CaseMetaModel();
    m.setName(name);
    m.setNamespace(namespace);
    m.setVersion(version);
    return m;
  }
}
