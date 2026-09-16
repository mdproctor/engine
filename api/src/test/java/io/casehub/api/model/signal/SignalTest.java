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
import java.util.Set;
import org.junit.jupiter.api.Test;

class SignalTest {

  @Test
  void validSignal_createsSuccessfully() {
    Instant now = Instant.now();
    Signal signal = new Signal("trail", 0.8, now, now, Duration.ofMinutes(5), "agent-a", 1, false);
    assertThat(signal.name()).isEqualTo("trail");
    assertThat(signal.strength()).isEqualTo(0.8);
    assertThat(signal.firstDeposited()).isEqualTo(now);
    assertThat(signal.lastReinforced()).isEqualTo(now);
    assertThat(signal.halfLife()).isEqualTo(Duration.ofMinutes(5));
    assertThat(signal.lastSource()).isEqualTo("agent-a");
    assertThat(signal.reinforcementCount()).isEqualTo(1);
    assertThat(signal.expired()).isFalse();
  }

  @Test
  void strength_belowZero_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Signal(
                    "x", -0.1, Instant.now(), Instant.now(), Duration.ofMinutes(1), "a", 1, false));
  }

  @Test
  void strength_aboveOne_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Signal(
                    "x", 1.1, Instant.now(), Instant.now(), Duration.ofMinutes(1), "a", 1, false));
  }

  @Test
  void halfLife_zero_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new Signal("x", 0.5, Instant.now(), Instant.now(), Duration.ZERO, "a", 1, false));
  }

  @Test
  void halfLife_negative_throws() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new Signal(
                    "x", 0.5, Instant.now(), Instant.now(), Duration.ofMinutes(-1), "a", 1, false));
  }

  @Test
  void sourcesFieldPresent() {
    Instant now = Instant.now();
    Signal signal =
        new Signal(
            "test", 1.0, now, now, Duration.ofMinutes(5), "agent-1", 1, false, Set.of("agent-1"));
    assertThat(signal.sources()).isEqualTo(Set.of("agent-1"));
  }

  @Test
  void backwardCompatConstructor_defaultsSourcesToLastSource() {
    Instant now = Instant.now();
    Signal signal = new Signal("test", 1.0, now, now, Duration.ofMinutes(5), "agent-1", 1, false);
    assertThat(signal.sources()).isEqualTo(Set.of("agent-1"));
  }

  @Test
  void backwardCompatConstructor_nullLastSource_emptySources() {
    Instant now = Instant.now();
    Signal signal = new Signal("test", 1.0, now, now, Duration.ofMinutes(5), null, 1, false);
    assertThat(signal.sources()).isEmpty();
  }
}
