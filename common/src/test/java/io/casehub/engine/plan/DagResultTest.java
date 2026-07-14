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
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.TaskStatus;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DagResultTest {

  @Test
  void completedResults_keyedByNodeId() {
    var result =
        new DagResult<>(
            Map.of("a", new NodeState.Completed<>("r1"), "b", new NodeState.Failed<>("err", null)),
            Map.of("a", "r1"),
            false,
            Duration.ofMillis(100));
    assertThat(result.completedResults()).containsEntry("a", "r1");
    assertThat(result.completedResults()).doesNotContainKey("b");
  }

  @Test
  void taskStatuses_projectsCorrectly() {
    var result =
        new DagResult<>(
            Map.of("a", new NodeState.Completed<>("r"), "b", new NodeState.Skipped<>("dep")),
            Map.of("a", "r"),
            false,
            Duration.ofMillis(50));
    assertThat(result.taskStatuses())
        .containsEntry("a", TaskStatus.COMPLETED)
        .containsEntry("b", TaskStatus.OBSOLETE);
  }

  @Test
  void allSucceeded_true_whenAllCompleted() {
    var result =
        new DagResult<>(
            Map.of("a", new NodeState.Completed<>("r")), Map.of("a", "r"), true, Duration.ZERO);
    assertThat(result.allSucceeded()).isTrue();
  }
}
