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

import io.casehub.api.model.stigmergy.ImprovementOutcome;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementCategoryTrackerTest {

  private ImprovementCategoryTracker tracker;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    tracker = new ImprovementCategoryTracker();
    caseId = UUID.randomUUID();
  }

  @Test
  void notSuppressedByDefault() {
    assertThat(tracker.isSuppressed(caseId, "dependency-update")).isFalse();
  }

  @Test
  void threeFailuresSuppresses() {
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    assertThat(tracker.isSuppressed(caseId, "lint-fix")).isFalse();
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    assertThat(tracker.isSuppressed(caseId, "lint-fix")).isTrue();
  }

  @Test
  void threeRejectionsSuppresses() {
    tracker.recordOutcome(caseId, "recipe", ImprovementOutcome.OutcomeStatus.REJECTED);
    tracker.recordOutcome(caseId, "recipe", ImprovementOutcome.OutcomeStatus.REJECTED);
    tracker.recordOutcome(caseId, "recipe", ImprovementOutcome.OutcomeStatus.REJECTED);
    assertThat(tracker.isSuppressed(caseId, "recipe")).isTrue();
  }

  @Test
  void pauseAndUnpause() {
    tracker.pauseCategory(caseId, "ci-triage", Duration.ofMinutes(60));
    assertThat(tracker.isSuppressed(caseId, "ci-triage")).isTrue();
    tracker.unpauseCategory(caseId, "ci-triage");
    assertThat(tracker.isSuppressed(caseId, "ci-triage")).isFalse();
  }

  @Test
  void successResetsFailureCount() {
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.MERGED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    assertThat(tracker.isSuppressed(caseId, "lint-fix")).isFalse();
  }

  @Test
  void differentCasesAreIndependent() {
    var otherCase = UUID.randomUUID();
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    assertThat(tracker.isSuppressed(otherCase, "lint-fix")).isFalse();
  }

  @Test
  void resetClearsAllState() {
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    tracker.reset();
    assertThat(tracker.isSuppressed(caseId, "lint-fix")).isFalse();
  }
}
