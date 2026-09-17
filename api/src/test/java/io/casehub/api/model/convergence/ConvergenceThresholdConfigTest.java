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

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConvergenceThresholdConfigTest {

  @Test
  void defaults_returns_sensible_values() {
    var config = ConvergenceThresholdConfig.defaults();
    assertThat(config.dispatchRateThreshold()).isEqualTo(0.1);
    assertThat(config.signalDepositRateThreshold()).isEqualTo(0.1);
    assertThat(config.contextMutationRateThreshold()).isEqualTo(0.1);
    assertThat(config.evaluationRateThreshold()).isEqualTo(0.5);
    assertThat(config.stabilityWindow()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.rateWindow()).isEqualTo(Duration.ofSeconds(60));
    assertThat(config.maxWindowEntries()).isNull();
  }

  @Test
  void rejects_negative_dispatch_threshold() {
    assertThatThrownBy(
            () ->
                new ConvergenceThresholdConfig(
                    -0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(30), Duration.ofSeconds(60), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_zero_stability_window() {
    assertThatThrownBy(
            () ->
                new ConvergenceThresholdConfig(
                    0.1, 0.1, 0.1, 0.5, Duration.ZERO, Duration.ofSeconds(60), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_negative_rate_window() {
    assertThatThrownBy(
            () ->
                new ConvergenceThresholdConfig(
                    0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(30), Duration.ofSeconds(-1), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_zero_max_window_entries() {
    assertThatThrownBy(
            () ->
                new ConvergenceThresholdConfig(
                    0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(30), Duration.ofSeconds(60), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void effectiveMaxWindowEntries_derived_from_rateWindow_when_null() {
    var config = ConvergenceThresholdConfig.defaults();
    assertThat(config.effectiveMaxWindowEntries()).isEqualTo(600);
  }

  @Test
  void effectiveMaxWindowEntries_uses_override_when_set() {
    var config =
        new ConvergenceThresholdConfig(
            0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(30), Duration.ofSeconds(60), 200);
    assertThat(config.effectiveMaxWindowEntries()).isEqualTo(200);
  }

  @Test
  void effectiveMaxWindowEntries_scales_with_rate_window() {
    var config =
        new ConvergenceThresholdConfig(
            0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(30), Duration.ofSeconds(120), null);
    assertThat(config.effectiveMaxWindowEntries()).isEqualTo(1200);
  }
}
