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
package io.casehub.engine.common.internal.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPoliciesTest {

  @Test
  void fixed_backoff_returns_constant_delay() {
    RetryPolicy policy = new RetryPolicy(3, 5000, BackoffStrategy.FIXED);
    RetryDecision d1 = RetryPolicies.evaluate(1, policy);
    RetryDecision d2 = RetryPolicies.evaluate(2, policy);

    assertThat(d1).isInstanceOf(RetryDecision.Retry.class);
    assertThat(((RetryDecision.Retry) d1).delay()).isEqualTo(Duration.ofMillis(5000));
    assertThat(((RetryDecision.Retry) d2).delay()).isEqualTo(Duration.ofMillis(5000));
  }

  @Test
  void exponential_backoff_doubles_each_attempt() {
    RetryPolicy policy = new RetryPolicy(5, 1000, BackoffStrategy.EXPONENTIAL);

    RetryDecision d1 = RetryPolicies.evaluate(1, policy);
    RetryDecision d2 = RetryPolicies.evaluate(2, policy);
    RetryDecision d3 = RetryPolicies.evaluate(3, policy);

    assertThat(((RetryDecision.Retry) d1).delay()).isEqualTo(Duration.ofMillis(1000));
    assertThat(((RetryDecision.Retry) d2).delay()).isEqualTo(Duration.ofMillis(2000));
    assertThat(((RetryDecision.Retry) d3).delay()).isEqualTo(Duration.ofMillis(4000));
  }

  @Test
  void exponential_backoff_caps_at_30_seconds() {
    RetryPolicy policy = new RetryPolicy(20, 10000, BackoffStrategy.EXPONENTIAL);
    RetryDecision d = RetryPolicies.evaluate(10, policy);

    assertThat(((RetryDecision.Retry) d).delay()).isEqualTo(Duration.ofMillis(30_000));
  }

  @Test
  void exponential_with_jitter_produces_bounded_delay() {
    RetryPolicy policy = new RetryPolicy(5, 1000, BackoffStrategy.EXPONENTIAL_WITH_JITTER);

    for (int i = 0; i < 20; i++) {
      RetryDecision d = RetryPolicies.evaluate(2, policy);
      assertThat(d).isInstanceOf(RetryDecision.Retry.class);
      long delayMs = ((RetryDecision.Retry) d).delay().toMillis();
      assertThat(delayMs).isBetween(0L, 2000L);
    }
  }

  @Test
  void exhaust_when_failure_count_reaches_max() {
    RetryPolicy policy = new RetryPolicy(3, 1000, BackoffStrategy.FIXED);
    RetryDecision d = RetryPolicies.evaluate(3, policy);

    assertThat(d).isInstanceOf(RetryDecision.Exhaust.class);
    assertThat(((RetryDecision.Exhaust) d).reason()).contains("3");
  }

  @Test
  void exhaust_when_failure_count_exceeds_max() {
    RetryPolicy policy = new RetryPolicy(3, 1000, BackoffStrategy.FIXED);
    RetryDecision d = RetryPolicies.evaluate(5, policy);

    assertThat(d).isInstanceOf(RetryDecision.Exhaust.class);
  }

  @Test
  void null_backoff_strategy_defaults_to_fixed() {
    RetryPolicy policy = new RetryPolicy(3, 5000, null);
    RetryDecision d = RetryPolicies.evaluate(1, policy);

    assertThat(d).isInstanceOf(RetryDecision.Retry.class);
    assertThat(((RetryDecision.Retry) d).delay()).isEqualTo(Duration.ofMillis(5000));
  }

  @Test
  void null_delay_defaults_to_zero() {
    RetryPolicy policy = new RetryPolicy(3, null, BackoffStrategy.FIXED);
    RetryDecision d = RetryPolicies.evaluate(1, policy);

    assertThat(d).isInstanceOf(RetryDecision.Retry.class);
    assertThat(((RetryDecision.Retry) d).delay()).isEqualTo(Duration.ZERO);
  }
}
