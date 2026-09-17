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
import org.junit.jupiter.api.Test;

class SlidingWindowCounterTest {

  @Test
  void empty_counter_returns_zero_rate() {
    var counter = new SlidingWindowCounter(100);
    assertThat(counter.rate(Duration.ofSeconds(60), Instant.now())).isEqualTo(0.0);
  }

  @Test
  void single_event_returns_correct_rate() {
    var counter = new SlidingWindowCounter(100);
    var now = Instant.now();
    counter.record(now);
    assertThat(counter.rate(Duration.ofSeconds(60), now)).isCloseTo(1.0 / 60, within(0.001));
  }

  @Test
  void events_outside_window_excluded() {
    var counter = new SlidingWindowCounter(100);
    var base = Instant.now();
    counter.record(base.minusSeconds(120));
    counter.record(base.minusSeconds(30));
    counter.record(base);
    assertThat(counter.rate(Duration.ofSeconds(60), base)).isCloseTo(2.0 / 60, within(0.001));
  }

  @Test
  void oldest_evicted_when_capacity_exceeded() {
    var counter = new SlidingWindowCounter(3);
    var base = Instant.now();
    counter.record(base.minusSeconds(3));
    counter.record(base.minusSeconds(2));
    counter.record(base.minusSeconds(1));
    counter.record(base);
    assertThat(counter.count()).isEqualTo(3);
  }

  @Test
  void total_returns_cumulative_count_including_evicted() {
    var counter = new SlidingWindowCounter(2);
    var now = Instant.now();
    counter.record(now);
    counter.record(now);
    counter.record(now);
    assertThat(counter.total()).isEqualTo(3);
    assertThat(counter.count()).isEqualTo(2);
  }

  @Test
  void reset_clears_all_state() {
    var counter = new SlidingWindowCounter(100);
    counter.record(Instant.now());
    counter.record(Instant.now());
    counter.reset();
    assertThat(counter.count()).isEqualTo(0);
    assertThat(counter.total()).isEqualTo(0);
    assertThat(counter.rate(Duration.ofSeconds(60), Instant.now())).isEqualTo(0.0);
  }

  @Test
  void rejects_zero_capacity() {
    assertThatThrownBy(() -> new SlidingWindowCounter(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void multiple_events_at_same_timestamp() {
    var counter = new SlidingWindowCounter(100);
    var now = Instant.now();
    counter.record(now);
    counter.record(now);
    counter.record(now);
    assertThat(counter.rate(Duration.ofSeconds(60), now)).isCloseTo(3.0 / 60, within(0.001));
  }
}
