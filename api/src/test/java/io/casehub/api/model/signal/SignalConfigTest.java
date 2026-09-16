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
package io.casehub.api.model.signal;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SignalConfigTest {

  @Test
  void defaults_returnsExpectedValues() {
    SignalConfig config = SignalConfig.defaults();
    assertThat(config.defaultHalfLife()).isEqualTo(Duration.ofMinutes(5));
    assertThat(config.effectiveZeroThreshold()).isEqualTo(0.01);
    assertThat(config.maxSignalsPerCase()).isEqualTo(100);
  }

  @Test
  void threshold_negative_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SignalConfig(Duration.ofMinutes(5), -0.01, 100));
  }

  @Test
  void threshold_aboveOne_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SignalConfig(Duration.ofMinutes(5), 1.1, 100));
  }

  @Test
  void maxSignals_zero_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SignalConfig(Duration.ofMinutes(5), 0.01, 0));
  }
}
