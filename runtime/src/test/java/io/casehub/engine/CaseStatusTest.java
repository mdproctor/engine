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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.casehub.api.model.CaseStatus;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link CaseStatus} conforms to the CNCF Serverless Workflow specification as
 * implemented by {@code io.serverlessworkflow.impl.WorkflowStatus} in Quarkus Flow.
 */
class CaseStatusTest {

  @Test
  void containsExactlyTheExpectedValues() {
    assertThat(EnumSet.allOf(CaseStatus.class))
        .containsExactlyInAnyOrder(
            CaseStatus.STARTING,
            CaseStatus.RUNNING,
            CaseStatus.WAITING,
            CaseStatus.SUSPENDED,
            CaseStatus.COMPLETED,
            CaseStatus.FAULTED,
            CaseStatus.CANCELLED,
            CaseStatus.COMPENSATING,
            CaseStatus.COMPENSATED,
            CaseStatus.COMPENSATION_FAULTED);
  }

  @Test
  void doesNotContainPending() {
    // PENDING is a casehub-blackboard concern (PlanItem/Stage lifecycle).
    // CaseInstance transitions directly to RUNNING on creation — there is no
    // observable PENDING window in the async event cycle.
    assertThat(EnumSet.allOf(CaseStatus.class))
        .extracting(CaseStatus::name)
        .doesNotContain("PENDING");
  }

  @Test
  void doesNotContainLegacyNames() {
    // These were the pre-alignment names — must not appear after the CNCF rename.
    assertThat(EnumSet.allOf(CaseStatus.class))
        .extracting(CaseStatus::name)
        .doesNotContain("ACTIVE", "FAILED", "TERMINATED");
  }

  @Test
  void allFourTerminalStatesArePresent() {
    assertThat(EnumSet.allOf(CaseStatus.class))
        .contains(CaseStatus.COMPLETED, CaseStatus.FAULTED, CaseStatus.CANCELLED, CaseStatus.COMPENSATED);
  }

  @Test
  void valueOfRoundTripsForAllValues() {
    // String serialisation round-trips matter — state is stored as VARCHAR in the DB.
    for (CaseStatus status : CaseStatus.values()) {
      assertThatCode(() -> CaseStatus.valueOf(status.name()))
          .as("valueOf must not throw for %s", status.name())
          .doesNotThrowAnyException();
      assertThat(CaseStatus.valueOf(status.name()))
          .as("valueOf must return same instance for %s", status.name())
          .isSameAs(status);
    }
  }

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
    var expected =
        java.util.Arrays.stream(CaseStatus.values()).filter(CaseStatus::isTerminal).toList();
    assertThat(CaseStatus.TERMINAL_STATUSES)
        .as("TERMINAL_STATUSES constant must match isTerminal() for all enum values")
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void terminalStatusesAreNeverActive() {
    for (CaseStatus status : CaseStatus.values()) {
      if (status.isTerminal()) {
        assertThat(status.isActive())
            .as("%s is both terminal and active — must be one or the other", status)
            .isFalse();
      }
    }
  }

  @Test
  void activeStatusesAreNeverTerminal() {
    for (CaseStatus status : CaseStatus.values()) {
      if (status.isActive()) {
        assertThat(status.isTerminal())
            .as("%s is both active and terminal — must be one or the other", status)
            .isFalse();
      }
    }
  }

  @Test
  void everyStatusIsEitherTerminalOrActive() {
    for (CaseStatus status : CaseStatus.values()) {
      assertThat(status.isTerminal() || status.isActive())
          .as("%s is neither terminal nor active — every status must be one or the other", status)
          .isTrue();
    }
  }
}
