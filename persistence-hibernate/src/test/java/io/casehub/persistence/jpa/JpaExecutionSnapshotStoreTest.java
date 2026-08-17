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

import io.casehub.engine.plan.execution.DagResultSnapshot;
import io.casehub.engine.plan.execution.ExecutionSnapshotStore;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import io.casehub.engine.plan.snapshot.LeafTaskSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@QuarkusTest
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JpaExecutionSnapshotStoreTest {

  @Inject ExecutionSnapshotStore store;

  @Test
  void storeAndRetrieveDagPlan() {
    UUID caseId = UUID.randomUUID();
    var snapshot = new DagPlanSnapshot(Map.of(), Instant.now());

    store.storeDagPlan(caseId, "test-tenant", snapshot);

    var result = store.getDagPlan(caseId, "test-tenant");
    assertThat(result).isPresent();
    assertThat(result.get().timestamp()).isNotNull();
  }

  @Test
  void storeAndRetrieveDagResult() {
    UUID caseId = UUID.randomUUID();
    var snapshot =
        new DagResultSnapshot(Map.of(), Map.of(), true, Duration.ofSeconds(1), Instant.now());

    store.storeDagResult(caseId, "test-tenant", snapshot);

    var result = store.getDagResult(caseId, "test-tenant");
    assertThat(result).isPresent();
    assertThat(result.get().allSucceeded()).isTrue();
  }

  @Test
  void storeAndRetrieveDecomposition() {
    UUID caseId = UUID.randomUUID();
    var snapshot =
        new DecompositionSnapshot(new LeafTaskSnapshot("l1", "desc", null), Instant.now());

    store.storeDecomposition(caseId, "test-tenant", snapshot);

    var result = store.getDecomposition(caseId, "test-tenant");
    assertThat(result).isPresent();
  }

  @Test
  void upsertOverwritesSingleColumn() {
    UUID caseId = UUID.randomUUID();
    store.storeDagPlan(caseId, "test-tenant", new DagPlanSnapshot(Map.of(), Instant.now()));
    store.storeDagResult(
        caseId,
        "test-tenant",
        new DagResultSnapshot(Map.of(), Map.of(), true, Duration.ofMillis(50), Instant.now()));

    assertThat(store.getDagPlan(caseId, "test-tenant")).isPresent();
    assertThat(store.getDagResult(caseId, "test-tenant")).isPresent();
  }

  @Test
  void tenantIsolation() {
    UUID caseId = UUID.randomUUID();
    store.storeDagPlan(caseId, "tenant-a", new DagPlanSnapshot(Map.of(), Instant.now()));

    assertThat(store.getDagPlan(caseId, "tenant-a")).isPresent();
    assertThat(store.getDagPlan(caseId, "tenant-b")).isEmpty();
  }

  @Test
  void evictRemovesAllSnapshots() {
    UUID caseId = UUID.randomUUID();
    store.storeDagPlan(caseId, "test-tenant", new DagPlanSnapshot(Map.of(), Instant.now()));
    store.storeDagResult(
        caseId,
        "test-tenant",
        new DagResultSnapshot(Map.of(), Map.of(), true, Duration.ofMillis(10), Instant.now()));

    store.evict(caseId);

    assertThat(store.getDagPlan(caseId, "test-tenant")).isEmpty();
    assertThat(store.getDagResult(caseId, "test-tenant")).isEmpty();
  }

  @Test
  void getReturnsEmptyForUnknownCase() {
    UUID caseId = UUID.randomUUID();
    assertThat(store.getDagPlan(caseId, "test-tenant")).isEmpty();
    assertThat(store.getDagResult(caseId, "test-tenant")).isEmpty();
    assertThat(store.getDecomposition(caseId, "test-tenant")).isEmpty();
  }
}
