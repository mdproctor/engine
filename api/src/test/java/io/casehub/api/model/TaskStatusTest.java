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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskStatusTest {

  @Test
  void terminalStates() {
    assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
    assertThat(TaskStatus.FAULTED.isTerminal()).isTrue();
    assertThat(TaskStatus.REJECTED.isTerminal()).isTrue();
    assertThat(TaskStatus.OBSOLETE.isTerminal()).isTrue();
    assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
  }

  @Test
  void activeStates() {
    assertThat(TaskStatus.PENDING.isActive()).isTrue();
    assertThat(TaskStatus.RUNNING.isActive()).isTrue();
    assertThat(TaskStatus.DELEGATED.isActive()).isTrue();
    assertThat(TaskStatus.SUSPENDED.isActive()).isTrue();
  }

  @Test
  void terminalAndActiveAreExhaustiveAndNonOverlapping() {
    for (TaskStatus status : TaskStatus.values()) {
      assertThat(status.isTerminal() ^ status.isActive())
          .as("Must be either terminal or active, never both, never neither: %s", status)
          .isTrue();
    }
  }

  @Test
  void allNineValuesPresent() {
    assertThat(TaskStatus.values()).hasSize(10);
  }
}
