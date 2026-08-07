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

import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DagResult;
import io.casehub.engine.plan.NodeState;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotCapturingDagEventListenerTest {

  @Test
  void constructorStoresDagPlanSnapshot() {
    var store = new InMemoryExecutionSnapshotStore();
    UUID caseId = UUID.randomUUID();
    var plan = DagPlan.singleton("node-0", "task");

    new SnapshotCapturingDagEventListener<>(caseId, store, plan);

    assertThat(store.getDagPlan(caseId, "t")).isPresent();
    assertThat(store.getDagPlan(caseId, "t").get().nodes()).hasSize(1);
  }

  @Test
  void onExecutionCompleteStoresDagResultSnapshot() {
    var store = new InMemoryExecutionSnapshotStore();
    UUID caseId = UUID.randomUUID();
    var plan = DagPlan.singleton("node-0", "task");
    var listener = new SnapshotCapturingDagEventListener<String, String>(caseId, store, plan);

    var result =
        new DagResult<>(
            Map.of("node-0", new NodeState.Completed<>("done")),
            Map.of("node-0", "done"),
            true,
            Duration.ofMillis(100));

    listener.onExecutionComplete(result);

    assertThat(store.getDagResult(caseId, "t")).isPresent();
    assertThat(store.getDagResult(caseId, "t").get().allSucceeded()).isTrue();
  }
}
