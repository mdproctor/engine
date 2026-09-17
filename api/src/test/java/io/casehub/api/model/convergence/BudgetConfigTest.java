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
package io.casehub.api.model.convergence;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BudgetConfigTest {

  @Test
  void defaults_returns_all_null_caps() {
    var config = BudgetConfig.defaults();
    assertThat(config.maxDispatches()).isNull();
    assertThat(config.maxSignalDeposits()).isNull();
    assertThat(config.maxContextMutations()).isNull();
    assertThat(config.maxEvaluationCycles()).isNull();
  }

  @Test
  void rejects_negative_dispatch_cap() {
    assertThatThrownBy(() -> new BudgetConfig(-1, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_negative_signal_cap() {
    assertThatThrownBy(() -> new BudgetConfig(null, -1, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accepts_zero_as_valid_cap() {
    var config = new BudgetConfig(0, 0, 0, 0);
    assertThat(config.maxDispatches()).isEqualTo(0);
    assertThat(config.maxSignalDeposits()).isEqualTo(0);
  }

  @Test
  void accepts_null_caps() {
    var config = new BudgetConfig(null, null, null, null);
    assertThat(config.maxDispatches()).isNull();
  }

  @Test
  void isExceeded_returns_true_when_count_exceeds_cap() {
    assertThat(BudgetConfig.isExceeded(101, 100)).isTrue();
  }

  @Test
  void isExceeded_returns_false_when_count_equals_cap() {
    assertThat(BudgetConfig.isExceeded(100, 100)).isFalse();
  }

  @Test
  void isExceeded_returns_false_when_cap_is_null() {
    assertThat(BudgetConfig.isExceeded(9999, null)).isFalse();
  }
}
