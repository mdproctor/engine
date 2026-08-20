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
package io.casehub.engine.plan.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.plan.snapshot.PlanVersionDelta;
import io.casehub.engine.plan.snapshot.PlanVersionTrigger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryPlanVersionStoreTest {

  private InMemoryPlanVersionStore store;
  private final UUID caseId = UUID.randomUUID();
  private final String tenancyId = "tenant-1";

  @BeforeEach
  void setUp() {
    store = new InMemoryPlanVersionStore();
  }

  @Test
  void storeAndRetrieveHistory() {
    PlanVersion v1 = version(1);
    PlanVersion v2 = version(2);
    store.store(v1, tenancyId);
    store.store(v2, tenancyId);

    List<PlanVersion> history = store.getHistory(caseId, tenancyId);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).version()).isEqualTo(1);
    assertThat(history.get(1).version()).isEqualTo(2);
  }

  @Test
  void getLatestReturnsHighestVersion() {
    store.store(version(1), tenancyId);
    store.store(version(2), tenancyId);
    store.store(version(3), tenancyId);

    assertThat(store.getLatest(caseId, tenancyId))
        .isPresent()
        .get()
        .extracting("version")
        .isEqualTo(3);
  }

  @Test
  void getVersionReturnsSpecificVersion() {
    store.store(version(1), tenancyId);
    store.store(version(2), tenancyId);

    assertThat(store.getVersion(caseId, 1, tenancyId))
        .isPresent()
        .get()
        .extracting("version")
        .isEqualTo(1);
    assertThat(store.getVersion(caseId, 2, tenancyId))
        .isPresent()
        .get()
        .extracting("version")
        .isEqualTo(2);
    assertThat(store.getVersion(caseId, 99, tenancyId)).isEmpty();
  }

  @Test
  void evictRemovesAllVersionsForCase() {
    store.store(version(1), tenancyId);
    store.store(version(2), tenancyId);
    store.evict(caseId);

    assertThat(store.getHistory(caseId, tenancyId)).isEmpty();
    assertThat(store.getLatest(caseId, tenancyId)).isEmpty();
  }

  @Test
  void getHistoryReturnsEmptyForUnknownCase() {
    assertThat(store.getHistory(UUID.randomUUID(), tenancyId)).isEmpty();
  }

  @Test
  void getLatestReturnsEmptyForUnknownCase() {
    assertThat(store.getLatest(UUID.randomUUID(), tenancyId)).isEmpty();
  }

  private PlanVersion version(int versionNum) {
    return new PlanVersion(
        versionNum,
        caseId,
        Instant.now(),
        new PlanVersionTrigger.InitialDecomposition("goal-" + versionNum, "llm"),
        null,
        new PlanVersionDelta(List.of("step-1"), List.of(), List.of("compound-1"), Map.of()));
  }
}
