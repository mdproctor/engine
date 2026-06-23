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
package io.casehub.resilience.backoff;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.RetryPolicy;
import org.junit.jupiter.api.Test;

/** Pure unit tests — no Quarkus, no CDI. BackoffDelayCalculator is a stateless utility. */
class BackoffDelayCalculatorTest {

  // ---- FIXED strategy -------------------------------------------------------

  @Test
  void fixed_alwaysReturnsConfiguredDelay() {
    RetryPolicy policy = new RetryPolicy(3, 1000, BackoffStrategy.FIXED);

    assertThat(BackoffDelayCalculator.calculate(policy, 1)).isEqualTo(1000);
    assertThat(BackoffDelayCalculator.calculate(policy, 2)).isEqualTo(1000);
    assertThat(BackoffDelayCalculator.calculate(policy, 3)).isEqualTo(1000);
  }

  @Test
  void fixed_zeroDelayStaysZero() {
    RetryPolicy policy = new RetryPolicy(3, 0, BackoffStrategy.FIXED);
    assertThat(BackoffDelayCalculator.calculate(policy, 1)).isEqualTo(0);
  }

  // ---- EXPONENTIAL strategy -------------------------------------------------

  @Test
  void exponential_doublesDelayEachAttempt() {
    RetryPolicy policy = new RetryPolicy(5, 1000, BackoffStrategy.EXPONENTIAL);

    assertThat(BackoffDelayCalculator.calculate(policy, 1)).isEqualTo(1000); // 1000 * 2^0
    assertThat(BackoffDelayCalculator.calculate(policy, 2)).isEqualTo(2000); // 1000 * 2^1
    assertThat(BackoffDelayCalculator.calculate(policy, 3)).isEqualTo(4000); // 1000 * 2^2
    assertThat(BackoffDelayCalculator.calculate(policy, 4)).isEqualTo(8000); // 1000 * 2^3
  }

  @Test
  void exponential_cappedAt30Seconds() {
    RetryPolicy policy = new RetryPolicy(20, 1000, BackoffStrategy.EXPONENTIAL);

    // 1000 * 2^15 = 32_768_000 → should be capped at 30_000
    assertThat(BackoffDelayCalculator.calculate(policy, 16)).isEqualTo(30_000);
    assertThat(BackoffDelayCalculator.calculate(policy, 20)).isEqualTo(30_000);
  }

  // ---- EXPONENTIAL_WITH_JITTER strategy -------------------------------------

  @Test
  void jitter_delayIsWithinExponentialBound() {
    RetryPolicy policy = new RetryPolicy(5, 1000, BackoffStrategy.EXPONENTIAL_WITH_JITTER);

    for (int attempt = 1; attempt <= 5; attempt++) {
      long delay = BackoffDelayCalculator.calculate(policy, attempt);
      long exponentialBound = Math.min(1000L * (1L << (attempt - 1)), 30_000L);
      assertThat(delay)
          .as("attempt %d delay should be in [0, %d]", attempt, exponentialBound)
          .isBetween(0L, exponentialBound);
    }
  }

  @Test
  void jitter_delayIsNonDeterministicAcrossMultipleCalls() {
    RetryPolicy policy = new RetryPolicy(5, 2000, BackoffStrategy.EXPONENTIAL_WITH_JITTER);

    // With a range of [0, 2000], the probability of getting the same value 10 times in a row
    // is astronomically small. This proves jitter is actually being applied.
    long first = BackoffDelayCalculator.calculate(policy, 2);
    boolean sawVariation = false;
    for (int i = 0; i < 20; i++) {
      if (BackoffDelayCalculator.calculate(policy, 2) != first) {
        sawVariation = true;
        break;
      }
    }
    assertThat(sawVariation).as("jitter should produce different values across calls").isTrue();
  }

  @Test
  void jitter_cappedAt30Seconds() {
    RetryPolicy policy = new RetryPolicy(20, 1000, BackoffStrategy.EXPONENTIAL_WITH_JITTER);

    for (int i = 0; i < 50; i++) {
      assertThat(BackoffDelayCalculator.calculate(policy, 20)).isLessThanOrEqualTo(30_000);
    }
  }

  // ---- Null/default safety --------------------------------------------------

  @Test
  void nullStrategy_treatedAsFixed() {
    // RetryPolicy constructed without strategy should behave like FIXED
    RetryPolicy policy = new RetryPolicy(3, 500);
    assertThat(BackoffDelayCalculator.calculate(policy, 1)).isEqualTo(500);
    assertThat(BackoffDelayCalculator.calculate(policy, 3)).isEqualTo(500);
  }
}
