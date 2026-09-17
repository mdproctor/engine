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

class OutputConvergenceConfigTest {

  @Test
  void defaults_returns_sensible_values() {
    var config = OutputConvergenceConfig.defaults();
    assertThat(config.convergenceThreshold()).isEqualTo(0.9);
    assertThat(config.convergenceMinSamples()).isEqualTo(3);
    assertThat(config.outputWindowSize()).isEqualTo(10);
  }

  @Test
  void rejects_threshold_above_one() {
    assertThatThrownBy(() -> new OutputConvergenceConfig(1.1, 3, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_negative_threshold() {
    assertThatThrownBy(() -> new OutputConvergenceConfig(-0.1, 3, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_min_samples_below_two() {
    assertThatThrownBy(() -> new OutputConvergenceConfig(0.9, 1, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_window_size_below_two() {
    assertThatThrownBy(() -> new OutputConvergenceConfig(0.9, 3, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void accepts_boundary_values() {
    var config = new OutputConvergenceConfig(0.0, 2, 2);
    assertThat(config.convergenceThreshold()).isEqualTo(0.0);
    assertThat(config.convergenceMinSamples()).isEqualTo(2);
    assertThat(config.outputWindowSize()).isEqualTo(2);
  }
}
