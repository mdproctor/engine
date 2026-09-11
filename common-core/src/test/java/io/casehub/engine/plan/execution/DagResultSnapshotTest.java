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

import io.casehub.engine.plan.DagResult;
import io.casehub.engine.plan.NodeState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DagResultSnapshotTest {

  @Test
  void fromDagResultMapsStatesAndResults() {
    var result =
        new DagResult<>(
            Map.of(
                "a", new NodeState.Completed<>("result-a"),
                "b", new NodeState.Failed<>("error", null)),
            Map.of("a", "result-a"),
            false,
            Duration.ofMillis(1500));
    var now = Instant.now();

    var snapshot = DagResultSnapshot.from(result, now);

    assertThat(snapshot.allSucceeded()).isFalse();
    assertThat(snapshot.elapsed()).isEqualTo(Duration.ofMillis(1500));
    assertThat(snapshot.timestamp()).isEqualTo(now);
    assertThat(snapshot.nodeStates()).hasSize(2);
    assertThat(snapshot.nodeStates().get("a").kind()).isEqualTo("Completed");
    assertThat(snapshot.nodeStates().get("b").kind()).isEqualTo("Failed");
    assertThat(snapshot.completedResults()).containsEntry("a", "result-a");
  }

  @Test
  void fromDagResultWithAllSucceeded() {
    var result =
        new DagResult<>(
            Map.of("a", new NodeState.Completed<>("done")),
            Map.of("a", "done"),
            true,
            Duration.ofMillis(500));

    var snapshot = DagResultSnapshot.from(result, Instant.now());

    assertThat(snapshot.allSucceeded()).isTrue();
    assertThat(snapshot.completedResults()).hasSize(1);
  }
}
