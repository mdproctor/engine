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
package io.casehub.engine.common.internal.convergence;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityTrackerTest {

  private final ActivityTracker tracker = new ActivityTracker();

  @Test
  void getState_returns_empty_state_for_unknown_case() {
    var state = tracker.getState(UUID.randomUUID());
    assertThat(state).isNotNull();
    assertThat(state.totalDispatches()).isEqualTo(0);
    assertThat(state.totalSignalDeposits()).isEqualTo(0);
    assertThat(state.totalContextMutations()).isEqualTo(0);
    assertThat(state.totalEvaluationCycles()).isEqualTo(0);
  }

  @Test
  void recordDispatch_increments_counter() {
    var caseId = UUID.randomUUID();
    tracker.recordDispatch(caseId);
    tracker.recordDispatch(caseId);
    assertThat(tracker.getState(caseId).totalDispatches()).isEqualTo(2);
  }

  @Test
  void recordSignalDeposit_increments_counter() {
    var caseId = UUID.randomUUID();
    tracker.recordSignalDeposit(caseId);
    assertThat(tracker.getState(caseId).totalSignalDeposits()).isEqualTo(1);
  }

  @Test
  void recordContextMutation_increments_by_key_count() {
    var caseId = UUID.randomUUID();
    tracker.recordContextMutation(caseId, 5);
    tracker.recordContextMutation(caseId, 3);
    assertThat(tracker.getState(caseId).totalContextMutations()).isEqualTo(8);
  }

  @Test
  void recordEvaluationCycle_increments_counter() {
    var caseId = UUID.randomUUID();
    tracker.recordEvaluationCycle(caseId);
    assertThat(tracker.getState(caseId).totalEvaluationCycles()).isEqualTo(1);
  }

  @Test
  void evictByCase_removes_state() {
    var caseId = UUID.randomUUID();
    tracker.recordDispatch(caseId);
    tracker.evictByCase(caseId);
    assertThat(tracker.getState(caseId).totalDispatches()).isEqualTo(0);
  }

  @Test
  void rates_return_zero_for_new_case() {
    var caseId = UUID.randomUUID();
    var state = tracker.getState(caseId);
    var now = Instant.now();
    assertThat(state.dispatchRate(Duration.ofSeconds(60), now)).isEqualTo(0.0);
    assertThat(state.signalDepositRate(Duration.ofSeconds(60), now)).isEqualTo(0.0);
    assertThat(state.contextMutationRate(Duration.ofSeconds(60), now)).isEqualTo(0.0);
    assertThat(state.evaluationRate(Duration.ofSeconds(60), now)).isEqualTo(0.0);
  }

  @Test
  void reset_clears_all_state() {
    var caseId = UUID.randomUUID();
    tracker.recordDispatch(caseId);
    tracker.recordSignalDeposit(caseId);
    tracker.reset();
    assertThat(tracker.getState(caseId).totalDispatches()).isEqualTo(0);
    assertThat(tracker.getState(caseId).totalSignalDeposits()).isEqualTo(0);
  }

  @Test
  void per_case_isolation() {
    var case1 = UUID.randomUUID();
    var case2 = UUID.randomUUID();
    tracker.recordDispatch(case1);
    tracker.recordDispatch(case1);
    tracker.recordDispatch(case2);
    assertThat(tracker.getState(case1).totalDispatches()).isEqualTo(2);
    assertThat(tracker.getState(case2).totalDispatches()).isEqualTo(1);
  }
}
