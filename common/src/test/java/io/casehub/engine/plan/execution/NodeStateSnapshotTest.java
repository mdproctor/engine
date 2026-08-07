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

import io.casehub.engine.plan.NodeState;
import org.junit.jupiter.api.Test;

class NodeStateSnapshotTest {

  @Test
  void fromPendingState() {
    var snapshot = NodeStateSnapshot.from(new NodeState.Pending<>());
    assertThat(snapshot.kind()).isEqualTo("Pending");
    assertThat(snapshot.reason()).isNull();
  }

  @Test
  void fromDispatchedState() {
    var snapshot = NodeStateSnapshot.from(new NodeState.Dispatched<>());
    assertThat(snapshot.kind()).isEqualTo("Dispatched");
    assertThat(snapshot.reason()).isNull();
  }

  @Test
  void fromCompletedState() {
    var snapshot = NodeStateSnapshot.from(new NodeState.Completed<>("result"));
    assertThat(snapshot.kind()).isEqualTo("Completed");
    assertThat(snapshot.reason()).isNull();
  }

  @Test
  void fromFailedStateWithReason() {
    var snapshot =
        NodeStateSnapshot.from(new NodeState.Failed<>("timeout", new RuntimeException("boom")));
    assertThat(snapshot.kind()).isEqualTo("Failed");
    assertThat(snapshot.reason()).isEqualTo("timeout");
  }

  @Test
  void fromSkippedState() {
    var snapshot = NodeStateSnapshot.from(new NodeState.Skipped<>("dependency failed"));
    assertThat(snapshot.kind()).isEqualTo("Skipped");
    assertThat(snapshot.reason()).isEqualTo("dependency failed");
  }

  @Test
  void fromCancelledState() {
    var snapshot = NodeStateSnapshot.from(new NodeState.Cancelled<>());
    assertThat(snapshot.kind()).isEqualTo("Cancelled");
    assertThat(snapshot.reason()).isNull();
  }
}
