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
package io.casehub.engine.planning.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.TaskStatus;
import org.junit.jupiter.api.Test;

class PlanItemExecutionStateTest {

  @Test
  void initial_status_is_pending() {
    var state = new PlanItemExecutionState("pi-1");
    assertThat(state.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void tryTransition_pending_to_running_succeeds() {
    var state = new PlanItemExecutionState("pi-1");
    assertThat(state.tryTransition(TaskStatus.PENDING, TaskStatus.RUNNING)).isTrue();
    assertThat(state.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void tryTransition_wrong_from_returns_false() {
    var state = new PlanItemExecutionState("pi-1");
    assertThat(state.tryTransition(TaskStatus.RUNNING, TaskStatus.COMPLETED)).isFalse();
    assertThat(state.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void tryTransition_to_terminal_succeeds() {
    var state = new PlanItemExecutionState("pi-1");
    state.tryTransition(TaskStatus.PENDING, TaskStatus.RUNNING);
    assertThat(state.tryTransition(TaskStatus.RUNNING, TaskStatus.COMPLETED)).isTrue();
    assertThat(state.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void tryTransition_from_terminal_fails() {
    var state = new PlanItemExecutionState("pi-1");
    state.tryTransition(TaskStatus.PENDING, TaskStatus.RUNNING);
    state.tryTransition(TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(state.tryTransition(TaskStatus.COMPLETED, TaskStatus.RUNNING)).isFalse();
  }

  @Test
  void forceTransition_from_any_non_terminal() {
    var state = new PlanItemExecutionState("pi-1");
    state.forceTransition(TaskStatus.FAULTED);
    assertThat(state.getStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void forceTransition_from_terminal_throws() {
    var state = new PlanItemExecutionState("pi-1");
    state.tryTransition(TaskStatus.PENDING, TaskStatus.RUNNING);
    state.tryTransition(TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThatThrownBy(() -> state.forceTransition(TaskStatus.RUNNING))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void planItemId_is_accessible() {
    var state = new PlanItemExecutionState("pi-42");
    assertThat(state.planItemId()).isEqualTo("pi-42");
  }

  @Test
  void restore_with_existing_status() {
    var state = PlanItemExecutionState.restore("pi-1", TaskStatus.RUNNING);
    assertThat(state.getStatus()).isEqualTo(TaskStatus.RUNNING);
    assertThat(state.tryTransition(TaskStatus.RUNNING, TaskStatus.COMPLETED)).isTrue();
  }

  @Test
  void isTerminal_delegates_to_status() {
    var state = new PlanItemExecutionState("pi-1");
    assertThat(state.isTerminal()).isFalse();
    state.tryTransition(TaskStatus.PENDING, TaskStatus.RUNNING);
    state.tryTransition(TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(state.isTerminal()).isTrue();
  }
}
