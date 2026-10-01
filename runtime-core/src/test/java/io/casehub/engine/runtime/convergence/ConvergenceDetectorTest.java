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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.convergence.ConvergenceThresholdConfig;
import io.casehub.engine.common.internal.convergence.CaseActivityState;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConvergenceDetectorTest {

  private final ConvergenceDetector detector = new ConvergenceDetector();

  @Test
  void no_config_returns_not_converged() {
    var state = new CaseActivityState(100);
    assertThat(detector.evaluate(UUID.randomUUID(), state, null, Instant.now())).isFalse();
  }

  @Test
  void all_rates_below_threshold_but_stability_window_not_met() {
    var caseId = UUID.randomUUID();
    var state = new CaseActivityState(100);
    var config = ConvergenceThresholdConfig.defaults();
    var now = Instant.now();
    assertThat(detector.evaluate(caseId, state, config, now)).isFalse();
    assertThat(detector.evaluate(caseId, state, config, now.plusSeconds(10))).isFalse();
  }

  @Test
  void convergence_detected_after_stability_window() {
    var caseId = UUID.randomUUID();
    var state = new CaseActivityState(100);
    var config =
        new ConvergenceThresholdConfig(
            0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(5), Duration.ofSeconds(60), null);
    var now = Instant.now();
    detector.evaluate(caseId, state, config, now);
    assertThat(detector.evaluate(caseId, state, config, now.plusSeconds(6))).isTrue();
  }

  @Test
  void convergence_fires_only_once() {
    var caseId = UUID.randomUUID();
    var state = new CaseActivityState(100);
    var config =
        new ConvergenceThresholdConfig(
            0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(1), Duration.ofSeconds(60), null);
    var now = Instant.now();
    detector.evaluate(caseId, state, config, now);
    assertThat(detector.evaluate(caseId, state, config, now.plusSeconds(2))).isTrue();
    assertThat(detector.evaluate(caseId, state, config, now.plusSeconds(3))).isFalse();
  }

  @Test
  void activity_resets_quiet_period() {
    var caseId = UUID.randomUUID();
    var state = new CaseActivityState(100);
    var config =
        new ConvergenceThresholdConfig(
            0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(5), Duration.ofSeconds(10), null);
    var now = Instant.now();
    detector.evaluate(caseId, state, config, now);
    state.recordDispatch(now.plusSeconds(3));
    assertThat(detector.evaluate(caseId, state, config, now.plusSeconds(4))).isFalse();
  }

  @Test
  void evict_clears_convergence_state_allows_re_detection() {
    var caseId = UUID.randomUUID();
    var state = new CaseActivityState(100);
    var config =
        new ConvergenceThresholdConfig(
            0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(1), Duration.ofSeconds(60), null);
    var now = Instant.now();
    detector.evaluate(caseId, state, config, now);
    assertThat(detector.evaluate(caseId, state, config, now.plusSeconds(2))).isTrue();
    detector.evictByCase(caseId);
    var later = now.plusSeconds(100);
    detector.evaluate(caseId, state, config, later);
    assertThat(detector.evaluate(caseId, state, config, later.plusSeconds(2))).isTrue();
  }

  @Test
  void reset_clears_all_state() {
    var caseId = UUID.randomUUID();
    var state = new CaseActivityState(100);
    var config =
        new ConvergenceThresholdConfig(
            0.1, 0.1, 0.1, 0.5, Duration.ofSeconds(1), Duration.ofSeconds(60), null);
    var now = Instant.now();
    detector.evaluate(caseId, state, config, now);
    detector.evaluate(caseId, state, config, now.plusSeconds(2));
    detector.reset();
    var later = now.plusSeconds(100);
    detector.evaluate(caseId, state, config, later);
    assertThat(detector.evaluate(caseId, state, config, later.plusSeconds(2))).isTrue();
  }
}
