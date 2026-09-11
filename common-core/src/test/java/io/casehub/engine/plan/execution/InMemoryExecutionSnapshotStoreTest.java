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

import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import io.casehub.engine.plan.snapshot.LeafTaskSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryExecutionSnapshotStoreTest {

  private final InMemoryExecutionSnapshotStore store = new InMemoryExecutionSnapshotStore();

  @Test
  void storeAndRetrieveDecomposition() {
    UUID caseId = UUID.randomUUID();
    var snapshot =
        new DecompositionSnapshot(new LeafTaskSnapshot("l1", "desc", null), Instant.now());

    store.storeDecomposition(caseId, "t", snapshot);

    assertThat(store.getDecomposition(caseId, "tenant-1")).contains(snapshot);
  }

  @Test
  void storeAndRetrieveDagPlan() {
    UUID caseId = UUID.randomUUID();
    var snapshot = new DagPlanSnapshot(Map.of(), Instant.now());

    store.storeDagPlan(caseId, "t", snapshot);

    assertThat(store.getDagPlan(caseId, "tenant-1")).contains(snapshot);
  }

  @Test
  void storeAndRetrieveDagResult() {
    UUID caseId = UUID.randomUUID();
    var snapshot =
        new DagResultSnapshot(Map.of(), Map.of(), true, Duration.ofSeconds(1), Instant.now());

    store.storeDagResult(caseId, "t", snapshot);

    assertThat(store.getDagResult(caseId, "tenant-1")).contains(snapshot);
  }

  @Test
  void evictRemovesAllSnapshots() {
    UUID caseId = UUID.randomUUID();
    store.storeDecomposition(
        caseId,
        "t",
        new DecompositionSnapshot(new LeafTaskSnapshot("l1", null, null), Instant.now()));
    store.storeDagPlan(caseId, "t", new DagPlanSnapshot(Map.of(), Instant.now()));

    store.evict(caseId);

    assertThat(store.getDecomposition(caseId, "t")).isEmpty();
    assertThat(store.getDagPlan(caseId, "t")).isEmpty();
  }

  @Test
  void getReturnsEmptyForUnknownCase() {
    assertThat(store.getDecomposition(UUID.randomUUID(), "t")).isEmpty();
    assertThat(store.getDagPlan(UUID.randomUUID(), "t")).isEmpty();
    assertThat(store.getDagResult(UUID.randomUUID(), "t")).isEmpty();
  }

  @Test
  void ttlSweepEvictsExpiredEntries() {
    var shortTtlStore = new InMemoryExecutionSnapshotStore(Duration.ZERO, Duration.ZERO);
    UUID caseId = UUID.randomUUID();
    shortTtlStore.storeDecomposition(
        caseId,
        "t",
        new DecompositionSnapshot(new LeafTaskSnapshot("l1", null, null), Instant.now()));

    assertThat(shortTtlStore.size()).isEqualTo(1);

    // Storing to another case triggers a sweep — the first entry should be evicted
    UUID caseId2 = UUID.randomUUID();
    shortTtlStore.storeDecomposition(
        caseId2,
        "t",
        new DecompositionSnapshot(new LeafTaskSnapshot("l2", null, null), Instant.now()));

    // The sweep runs on store, so caseId should be gone (TTL=0 means immediately expired)
    assertThat(shortTtlStore.getDecomposition(caseId, "t")).isEmpty();
  }

  @Test
  void storeOverwritesPreviousSnapshot() {
    UUID caseId = UUID.randomUUID();
    var first = new DagPlanSnapshot(Map.of(), Instant.now());
    var second =
        new DagPlanSnapshot(
            Map.of(
                "n1",
                new io.casehub.engine.plan.snapshot.DagNodeSnapshot(
                    "n1",
                    "t1",
                    "d",
                    "e",
                    java.util.Set.of(),
                    io.casehub.engine.plan.JoinType.ALL_OF)),
            Instant.now());

    store.storeDagPlan(caseId, "t", first);
    store.storeDagPlan(caseId, "t", second);

    assertThat(store.getDagPlan(caseId, "t")).contains(second);
    assertThat(store.getDagPlan(caseId, "t").get().nodes()).hasSize(1);
  }
}
