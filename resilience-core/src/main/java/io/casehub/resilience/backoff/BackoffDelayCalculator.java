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

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.RetryPolicy;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stateless utility that computes the retry delay in milliseconds for a given {@link RetryPolicy}
 * and attempt number (1-based). Exponential variants are capped at {@link #MAX_DELAY_MS} (30s) to
 * prevent unbounded growth.
 */
public final class BackoffDelayCalculator {

  static final long MAX_DELAY_MS = 30_000L;

  private BackoffDelayCalculator() {}

  /**
   * Returns the delay in milliseconds before attempt {@code attemptNumber} (1-based).
   *
   * <p>A {@code null} or missing {@link BackoffStrategy} is treated as {@link
   * BackoffStrategy#FIXED}.
   */
  public static long calculate(RetryPolicy policy, int attemptNumber) {
    long baseDelayMs = policy.delayMs() != null ? policy.delayMs() : 0L;
    BackoffStrategy strategy =
        policy.backoffStrategy() != null ? policy.backoffStrategy() : BackoffStrategy.FIXED;

    return switch (strategy) {
      case FIXED -> baseDelayMs;
      case EXPONENTIAL -> exponential(baseDelayMs, attemptNumber);
      case EXPONENTIAL_WITH_JITTER -> jitter(baseDelayMs, attemptNumber);
    };
  }

  private static long exponential(long baseDelayMs, int attempt) {
    // baseDelayMs * 2^(attempt-1), capped at MAX_DELAY_MS
    long shift = Math.min(attempt - 1, 30); // guard against overflow on very large attempt counts
    return Math.min(baseDelayMs * (1L << shift), MAX_DELAY_MS);
  }

  private static long jitter(long baseDelayMs, int attempt) {
    long cap = exponential(baseDelayMs, attempt);
    return cap == 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1);
  }
}
