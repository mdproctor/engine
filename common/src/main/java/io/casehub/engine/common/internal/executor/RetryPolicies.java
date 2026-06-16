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

import io.casehub.api.model.BackoffStrategy;
import io.casehub.api.model.RetryPolicy;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Static utility for retry backoff computation. Pure math — no dependencies, no CDI. Moved from
 * {@code QuartzWorkerExecutionJobListener.computeBackoffDelayMs()} so any scheduler adapter can
 * reuse the same backoff logic.
 *
 * <p>Refs casehubio/engine#463.
 */
public final class RetryPolicies {

  private RetryPolicies() {}

  public static RetryDecision evaluate(int failureCount, RetryPolicy policy) {
    if (failureCount >= policy.maxAttempts()) {
      return new RetryDecision.Exhaust(
          "Max attempts exceeded: " + failureCount + "/" + policy.maxAttempts());
    }
    long delayMs = computeBackoffDelayMs(policy, failureCount);
    return new RetryDecision.Retry(Duration.ofMillis(delayMs));
  }

  private static long computeBackoffDelayMs(RetryPolicy policy, long attemptNumber) {
    long baseDelayMs = policy.delayMs() != null ? policy.delayMs() : 0L;
    BackoffStrategy strategy =
        policy.backoffStrategy() != null ? policy.backoffStrategy() : BackoffStrategy.FIXED;
    return switch (strategy) {
      case FIXED -> baseDelayMs;
      case EXPONENTIAL -> {
        long shift = Math.min(attemptNumber - 1, 30);
        yield Math.min(baseDelayMs * (1L << shift), 30_000L);
      }
      case EXPONENTIAL_WITH_JITTER -> {
        long shift = Math.min(attemptNumber - 1, 30);
        long cap = Math.min(baseDelayMs * (1L << shift), 30_000L);
        yield cap == 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1);
      }
    };
  }
}
