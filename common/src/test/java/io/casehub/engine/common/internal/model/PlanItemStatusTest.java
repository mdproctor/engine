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
package io.casehub.engine.common.internal.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PlanItemStatusTest {

  private static final Set<PlanItemStatus> EXPECTED_TERMINAL =
      EnumSet.of(
          PlanItemStatus.COMPLETED,
          PlanItemStatus.FAULTED,
          PlanItemStatus.REJECTED,
          PlanItemStatus.OBSOLETE,
          PlanItemStatus.CANCELLED);

  private static final Set<PlanItemStatus> EXPECTED_ACTIVE =
      EnumSet.of(PlanItemStatus.PENDING, PlanItemStatus.RUNNING, PlanItemStatus.DELEGATED);

  @ParameterizedTest
  @EnumSource(PlanItemStatus.class)
  void isTerminal_matchesExpectedSet(final PlanItemStatus status) {
    assertThat(status.isTerminal())
        .as("isTerminal() for %s", status)
        .isEqualTo(EXPECTED_TERMINAL.contains(status));
  }

  @ParameterizedTest
  @EnumSource(PlanItemStatus.class)
  void isActive_matchesExpectedSet(final PlanItemStatus status) {
    assertThat(status.isActive())
        .as("isActive() for %s", status)
        .isEqualTo(EXPECTED_ACTIVE.contains(status));
  }

  @Test
  void terminalAndActive_areDisjoint() {
    for (final PlanItemStatus status : PlanItemStatus.values()) {
      assertThat(status.isTerminal() && status.isActive())
          .as("%s must not be both terminal and active", status)
          .isFalse();
    }
  }

  @Test
  void everyStatus_isEitherTerminalOrActive() {
    for (final PlanItemStatus status : PlanItemStatus.values()) {
      assertThat(status.isTerminal() || status.isActive())
          .as("%s must be either terminal or active", status)
          .isTrue();
    }
  }

  @Test
  void obsolete_exists() {
    assertThat(PlanItemStatus.valueOf("OBSOLETE")).isEqualTo(PlanItemStatus.OBSOLETE);
  }
}
