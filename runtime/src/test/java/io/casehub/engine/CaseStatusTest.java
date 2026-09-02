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
  void isTerminal_trueForTerminalStates() {
    assertThat(CaseStatus.COMPLETED.isTerminal()).isTrue();
    assertThat(CaseStatus.FAULTED.isTerminal()).isTrue();
    assertThat(CaseStatus.CANCELLED.isTerminal()).isTrue();
  }

  @Test
  void isTerminal_falseForNonTerminalStates() {
    assertThat(CaseStatus.STARTING.isTerminal()).isFalse();
    assertThat(CaseStatus.RUNNING.isTerminal()).isFalse();
    assertThat(CaseStatus.WAITING.isTerminal()).isFalse();
    assertThat(CaseStatus.SUSPENDED.isTerminal()).isFalse();
  }

  @Test
  void isActive_trueForActiveStates() {
    assertThat(CaseStatus.STARTING.isActive()).isTrue();
    assertThat(CaseStatus.RUNNING.isActive()).isTrue();
    assertThat(CaseStatus.WAITING.isActive()).isTrue();
  }

  @Test
  void isActive_falseForInactiveStates() {
    assertThat(CaseStatus.SUSPENDED.isActive()).isFalse();
    assertThat(CaseStatus.COMPLETED.isActive()).isFalse();
    assertThat(CaseStatus.FAULTED.isActive()).isFalse();
    assertThat(CaseStatus.CANCELLED.isActive()).isFalse();
  }

  @Test
  void terminalStatuses_containsExactlyTerminalValues() {
    assertThat(CaseStatus.terminalStatuses())
        .containsExactlyInAnyOrder(CaseStatus.COMPLETED, CaseStatus.FAULTED, CaseStatus.CANCELLED);
  }

  @Test
  void isTerminal_and_isActive_areDisjoint() {
    for (CaseStatus status : CaseStatus.values()) {
      assertThat(status.isTerminal() && status.isActive())
          .as("%s cannot be both terminal and active", status)
          .isFalse();
    }
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
}
