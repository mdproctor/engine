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
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SignalDecayTest {

  @Test
  void noElapsedTime_returnsOriginalStrength() {
    Instant now = Instant.now();
    assertThat(SignalDecay.effectiveStrength(0.8, now, Duration.ofMinutes(5), now)).isEqualTo(0.8);
  }

  @Test
  void oneHalfLife_returnsHalfStrength() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant after = start.plus(Duration.ofMinutes(5));
    double result = SignalDecay.effectiveStrength(1.0, start, Duration.ofMinutes(5), after);
    assertThat(result).isCloseTo(0.5, within(0.001));
  }

  @Test
  void twoHalfLives_returnsQuarterStrength() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant after = start.plus(Duration.ofMinutes(10));
    double result = SignalDecay.effectiveStrength(1.0, start, Duration.ofMinutes(5), after);
    assertThat(result).isCloseTo(0.25, within(0.001));
  }

  @Test
  void futureTimestamp_returnsOriginalStrength() {
    Instant start = Instant.parse("2026-01-01T00:05:00Z");
    Instant before = Instant.parse("2026-01-01T00:00:00Z");
    assertThat(SignalDecay.effectiveStrength(0.8, start, Duration.ofMinutes(5), before))
        .isEqualTo(0.8);
  }

  @Test
  void veryLongElapsed_decaysNearZero() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant after = start.plus(Duration.ofHours(1));
    double result = SignalDecay.effectiveStrength(1.0, start, Duration.ofMinutes(5), after);
    assertThat(result).isLessThan(0.001);
  }

  @Test
  void partialStrength_decaysProportionally() {
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    Instant after = start.plus(Duration.ofMinutes(5));
    double result = SignalDecay.effectiveStrength(0.6, start, Duration.ofMinutes(5), after);
    assertThat(result).isCloseTo(0.3, within(0.001));
  }
}
