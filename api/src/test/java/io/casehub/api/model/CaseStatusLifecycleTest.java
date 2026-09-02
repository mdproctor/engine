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

import java.util.Arrays;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CaseStatusLifecycleTest {

  @Test
  void completed_isTerminal() {
    assertThat(CaseStatus.COMPLETED.isTerminal()).isTrue();
  }

  @Test
  void faulted_isTerminal() {
    assertThat(CaseStatus.FAULTED.isTerminal()).isTrue();
  }

  @Test
  void cancelled_isTerminal() {
    assertThat(CaseStatus.CANCELLED.isTerminal()).isTrue();
  }

  @Test
  void starting_isNotTerminal() {
    assertThat(CaseStatus.STARTING.isTerminal()).isFalse();
  }

  @Test
  void running_isNotTerminal() {
    assertThat(CaseStatus.RUNNING.isTerminal()).isFalse();
  }

  @Test
  void waiting_isNotTerminal() {
    assertThat(CaseStatus.WAITING.isTerminal()).isFalse();
  }

  @Test
  void suspended_isNotTerminal() {
    assertThat(CaseStatus.SUSPENDED.isTerminal()).isFalse();
  }

  @Test
  void starting_isActive() {
    assertThat(CaseStatus.STARTING.isActive()).isTrue();
  }

  @Test
  void running_isActive() {
    assertThat(CaseStatus.RUNNING.isActive()).isTrue();
  }

  @Test
  void waiting_isActive() {
    assertThat(CaseStatus.WAITING.isActive()).isTrue();
  }

  @Test
  void suspended_isActive() {
    assertThat(CaseStatus.SUSPENDED.isActive()).isTrue();
  }

  @Test
  void completed_isNotActive() {
    assertThat(CaseStatus.COMPLETED.isActive()).isFalse();
  }

  @Test
  void faulted_isNotActive() {
    assertThat(CaseStatus.FAULTED.isActive()).isFalse();
  }

  @Test
  void cancelled_isNotActive() {
    assertThat(CaseStatus.CANCELLED.isActive()).isFalse();
  }

  @Test
  void terminalStatusesConstant_matchesIsTerminal() {
    var expected = Arrays.stream(CaseStatus.values()).filter(CaseStatus::isTerminal).toList();
    assertThat(CaseStatus.TERMINAL_STATUSES)
        .as("TERMINAL_STATUSES constant must match isTerminal() for all enum values")
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void terminalStatusesAreNeverActive() {
    for (CaseStatus status : CaseStatus.values()) {
      if (status.isTerminal()) {
        assertThat(status.isActive()).as("%s is both terminal and active", status).isFalse();
      }
    }
  }

  @Test
  void activeStatusesAreNeverTerminal() {
    for (CaseStatus status : CaseStatus.values()) {
      if (status.isActive()) {
        assertThat(status.isTerminal()).as("%s is both active and terminal", status).isFalse();
      }
    }
  }

  @Test
  void everyStatusIsEitherTerminalOrActive() {
    for (CaseStatus status : CaseStatus.values()) {
      assertThat(status.isTerminal() || status.isActive())
          .as("%s is neither terminal nor active", status)
          .isTrue();
    }
  }

  @Test
  void exactlyThreeTerminalStatuses() {
    assertThat(CaseStatus.TERMINAL_STATUSES)
        .containsExactlyInAnyOrder(CaseStatus.COMPLETED, CaseStatus.FAULTED, CaseStatus.CANCELLED);
  }

  @Test
  void exactlyFourActiveStatuses() {
    var active = EnumSet.allOf(CaseStatus.class).stream().filter(CaseStatus::isActive).toList();
    assertThat(active)
        .containsExactlyInAnyOrder(
            CaseStatus.STARTING, CaseStatus.RUNNING, CaseStatus.WAITING, CaseStatus.SUSPENDED);
  }
}
