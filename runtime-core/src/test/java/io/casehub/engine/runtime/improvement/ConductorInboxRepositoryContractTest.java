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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.api.model.stigmergy.ConductorInboxEntry.Status;
import io.casehub.engine.common.spi.ConductorInboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class ConductorInboxRepositoryContractTest {

  protected abstract ConductorInboxRepository createStore();

  protected abstract String tenancyId();

  private ConductorInboxRepository store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  protected ConductorInboxEntry makeEntry(UUID caseId, String id, String stage, Status status) {
    return new ConductorInboxEntry(
        caseId,
        id,
        stage,
        status,
        null,
        null,
        null,
        "test",
        List.of(),
        0.8,
        Instant.now(),
        null,
        null,
        null);
  }

  @Test
  void saveAndFindById() {
    var caseId = UUID.randomUUID();
    var entry = makeEntry(caseId, "e1", CodeEvolutionStages.RESEARCH_SCOPE, Status.PENDING);

    store.save(entry, tenancyId());

    var found = store.findById(caseId, "e1", tenancyId());
    assertThat(found).isNotNull();
    assertThat(found.id()).isEqualTo("e1");
    assertThat(found.status()).isEqualTo(Status.PENDING);
  }

  @Test
  void findPendingFiltersByStatus() {
    var caseId = UUID.randomUUID();
    store.save(
        makeEntry(caseId, "e1", CodeEvolutionStages.RESEARCH_SCOPE, Status.PENDING), tenancyId());
    store.save(
        makeEntry(caseId, "e2", CodeEvolutionStages.HYPOTHESIS_APPROVAL, Status.APPROVED),
        tenancyId());
    store.save(makeEntry(caseId, "e3", CodeEvolutionStages.PR_REVIEW, Status.PENDING), tenancyId());

    var pending = store.findPending(caseId, tenancyId());
    assertThat(pending).hasSize(2);
    assertThat(pending).extracting(ConductorInboxEntry::id).containsExactlyInAnyOrder("e1", "e3");
  }

  @Test
  void countPendingMatchesPendingSize() {
    var caseId = UUID.randomUUID();
    store.save(
        makeEntry(caseId, "e1", CodeEvolutionStages.RESEARCH_SCOPE, Status.PENDING), tenancyId());
    store.save(
        makeEntry(caseId, "e2", CodeEvolutionStages.HYPOTHESIS_APPROVAL, Status.APPROVED),
        tenancyId());

    assertThat(store.countPending(caseId, tenancyId())).isEqualTo(1);
  }

  @Test
  void findAllReturnsAllEntries() {
    var caseId = UUID.randomUUID();
    store.save(
        makeEntry(caseId, "e1", CodeEvolutionStages.RESEARCH_SCOPE, Status.PENDING), tenancyId());
    store.save(
        makeEntry(caseId, "e2", CodeEvolutionStages.HYPOTHESIS_APPROVAL, Status.APPROVED),
        tenancyId());

    assertThat(store.findAll(caseId, tenancyId())).hasSize(2);
  }

  @Test
  void perCaseIsolation() {
    var case1 = UUID.randomUUID();
    var case2 = UUID.randomUUID();
    store.save(
        makeEntry(case1, "e1", CodeEvolutionStages.RESEARCH_SCOPE, Status.PENDING), tenancyId());
    store.save(
        makeEntry(case2, "e2", CodeEvolutionStages.RESEARCH_SCOPE, Status.PENDING), tenancyId());

    assertThat(store.findAll(case1, tenancyId())).hasSize(1);
    assertThat(store.findAll(case1, tenancyId()).get(0).id()).isEqualTo("e1");
    assertThat(store.findAll(case2, tenancyId())).hasSize(1);
    assertThat(store.findAll(case2, tenancyId()).get(0).id()).isEqualTo("e2");
  }

  @Test
  void saveIdempotency() {
    var caseId = UUID.randomUUID();
    store.save(
        makeEntry(caseId, "e1", CodeEvolutionStages.RESEARCH_SCOPE, Status.PENDING), tenancyId());
    store.save(
        makeEntry(caseId, "e1", CodeEvolutionStages.RESEARCH_SCOPE, Status.APPROVED), tenancyId());

    assertThat(store.findAll(caseId, tenancyId())).hasSize(1);
    assertThat(store.findById(caseId, "e1", tenancyId()).status()).isEqualTo(Status.APPROVED);
  }

  @Test
  void findByIdReturnsNullForUnknown() {
    assertThat(store.findById(UUID.randomUUID(), "nonexistent", tenancyId())).isNull();
  }

  @Test
  void findPendingReturnsEmptyForUnknownCase() {
    assertThat(store.findPending(UUID.randomUUID(), tenancyId())).isEmpty();
  }

  @Test
  void countPendingReturnsZeroForUnknownCase() {
    assertThat(store.countPending(UUID.randomUUID(), tenancyId())).isZero();
  }
}
