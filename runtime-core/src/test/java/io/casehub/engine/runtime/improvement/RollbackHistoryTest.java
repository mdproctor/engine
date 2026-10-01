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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RollbackHistoryTest {

  private RollbackHistory history;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    history = new RollbackHistory();
    caseId = UUID.randomUUID();
  }

  @Test
  void emptyHistoryReturnsFalse() {
    assertThat(
            history.wasRecentlyRolledBack(caseId, "lint-fix", "checkstyle", Duration.ofMinutes(60)))
        .isFalse();
  }

  @Test
  void recentRollbackDetected() {
    history.record(caseId, UUID.randomUUID(), "lint-fix", "checkstyle");
    assertThat(
            history.wasRecentlyRolledBack(caseId, "lint-fix", "checkstyle", Duration.ofMinutes(60)))
        .isTrue();
  }

  @Test
  void differentCategoryNotDetected() {
    history.record(caseId, UUID.randomUUID(), "lint-fix", "checkstyle");
    assertThat(
            history.wasRecentlyRolledBack(
                caseId, "dependency-update", "checkstyle", Duration.ofMinutes(60)))
        .isFalse();
  }

  @Test
  void differentTargetNotDetected() {
    history.record(caseId, UUID.randomUUID(), "lint-fix", "checkstyle");
    assertThat(
            history.wasRecentlyRolledBack(caseId, "lint-fix", "spotbugs", Duration.ofMinutes(60)))
        .isFalse();
  }

  @Test
  void resetClearsHistory() {
    history.record(caseId, UUID.randomUUID(), "lint-fix", "checkstyle");
    history.reset();
    assertThat(
            history.wasRecentlyRolledBack(caseId, "lint-fix", "checkstyle", Duration.ofMinutes(60)))
        .isFalse();
  }
}
