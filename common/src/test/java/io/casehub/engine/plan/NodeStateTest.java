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
import org.junit.jupiter.api.Test;

class NodeStateTest {

  @Test
  void pending_isNotTerminal() {
    assertThat(new NodeState.Pending<>().isTerminal()).isFalse();
  }

  @Test
  void dispatched_isNotTerminal() {
    assertThat(new NodeState.Dispatched<>().isTerminal()).isFalse();
  }

  @Test
  void completed_isTerminal() {
    assertThat(new NodeState.Completed<>("result").isTerminal()).isTrue();
  }

  @Test
  void failed_isTerminal() {
    assertThat(new NodeState.Failed<>("err", null).isTerminal()).isTrue();
  }

  @Test
  void skipped_isTerminal() {
    assertThat(new NodeState.Skipped<>("dep failed").isTerminal()).isTrue();
  }

  @Test
  void cancelled_isTerminal() {
    assertThat(new NodeState.Cancelled<>().isTerminal()).isTrue();
  }

  @Test
  void toTaskStatus_allMappings() {
    assertThat(new NodeState.Pending<>().toTaskStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(new NodeState.Dispatched<>().toTaskStatus()).isEqualTo(TaskStatus.RUNNING);
    assertThat(new NodeState.Completed<>("r").toTaskStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(new NodeState.Failed<>("e", null).toTaskStatus()).isEqualTo(TaskStatus.FAULTED);
    assertThat(new NodeState.Skipped<>("s").toTaskStatus()).isEqualTo(TaskStatus.OBSOLETE);
    assertThat(new NodeState.Cancelled<>().toTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
  }
}
