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
package io.casehub.engine.internal.convergence;

import static org.assertj.core.api.Assertions.*;

import io.casehub.api.model.convergence.BudgetConfig;
import org.junit.jupiter.api.Test;

class BudgetEnforcerTest {

  private final BudgetEnforcer enforcer = new BudgetEnforcer();

  @Test
  void no_budget_config_returns_false() {
    assertThat(enforcer.isExhausted(null, 0, 0, 0, 0)).isFalse();
  }

  @Test
  void all_null_caps_returns_false() {
    var config = BudgetConfig.defaults();
    assertThat(enforcer.isExhausted(config, 999, 999, 999, 999)).isFalse();
  }

  @Test
  void dispatch_cap_exceeded_returns_true() {
    var config = new BudgetConfig(10, null, null, null);
    assertThat(enforcer.isExhausted(config, 11, 0, 0, 0)).isTrue();
  }

  @Test
  void dispatch_cap_not_exceeded_returns_false() {
    var config = new BudgetConfig(10, null, null, null);
    assertThat(enforcer.isExhausted(config, 10, 0, 0, 0)).isFalse();
  }

  @Test
  void signal_cap_exceeded_returns_true() {
    var config = new BudgetConfig(null, 5, null, null);
    assertThat(enforcer.isExhausted(config, 0, 6, 0, 0)).isTrue();
  }

  @Test
  void context_mutation_cap_exceeded_returns_true() {
    var config = new BudgetConfig(null, null, 100, null);
    assertThat(enforcer.isExhausted(config, 0, 0, 101, 0)).isTrue();
  }

  @Test
  void evaluation_cycle_cap_exceeded_returns_true() {
    var config = new BudgetConfig(null, null, null, 50);
    assertThat(enforcer.isExhausted(config, 0, 0, 0, 51)).isTrue();
  }

  @Test
  void exhaustedMetric_identifies_dispatches() {
    var config = new BudgetConfig(10, null, null, null);
    assertThat(enforcer.exhaustedMetric(config, 11, 0, 0, 0)).isEqualTo("dispatches");
  }

  @Test
  void exhaustedMetric_identifies_signalDeposits() {
    var config = new BudgetConfig(null, 5, null, null);
    assertThat(enforcer.exhaustedMetric(config, 0, 6, 0, 0)).isEqualTo("signalDeposits");
  }

  @Test
  void exhaustedMetric_identifies_contextMutations() {
    var config = new BudgetConfig(null, null, 100, null);
    assertThat(enforcer.exhaustedMetric(config, 0, 0, 101, 0)).isEqualTo("contextMutations");
  }

  @Test
  void exhaustedMetric_identifies_evaluationCycles() {
    var config = new BudgetConfig(null, null, null, 50);
    assertThat(enforcer.exhaustedMetric(config, 0, 0, 0, 51)).isEqualTo("evaluationCycles");
  }

  @Test
  void exhaustedMetric_returns_null_when_none_exceeded() {
    var config = new BudgetConfig(10, 10, 10, 10);
    assertThat(enforcer.exhaustedMetric(config, 5, 5, 5, 5)).isNull();
  }

  @Test
  void exhaustedMetric_returns_null_for_null_config() {
    assertThat(enforcer.exhaustedMetric(null, 999, 999, 999, 999)).isNull();
  }
}
