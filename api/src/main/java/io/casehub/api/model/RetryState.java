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
package io.casehub.api.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Explicit retry attempt history for a worker execution or plan item. Records every retry attempt
 * with timestamp, error message, duration, and success flag.
 *
 * <p>Attached to {@link io.casehub.api.engine.PlanExecutionContext} for {@code PlanningStrategy}
 * reasoning and {@code DeadLetterEntry} for DLQ enrichment.
 *
 * <p>Use {@link #empty()} to create an instance with no attempts. Use {@link #of(List, Instant,
 * Instant)} to create an instance from a list of attempts.
 *
 * @param attemptCount total number of retry attempts recorded
 * @param attempts ordered list of retry attempts, oldest first
 * @param firstAttemptTime timestamp of the first retry attempt, null if no attempts
 * @param lastAttemptTime timestamp of the most recent retry attempt, null if no attempts
 */
public record RetryState(
    int attemptCount,
    List<RetryAttempt> attempts,
    Instant firstAttemptTime,
    Instant lastAttemptTime) {

  /**
   * A single retry attempt record.
   *
   * @param timestamp when the attempt occurred
   * @param errorMessage the error message from the failure, null if succeeded
   * @param duration how long the attempt took
   * @param succeeded whether the attempt succeeded
   */
  public record RetryAttempt(
      Instant timestamp, String errorMessage, Duration duration, boolean succeeded) {}

  /**
   * Creates an empty retry state with no attempts.
   *
   * @return a retry state with attemptCount=0 and empty attempts list
   */
  public static RetryState empty() {
    return new RetryState(0, List.of(), null, null);
  }

  /**
   * Creates a retry state from a list of attempts.
   *
   * @param attempts the list of retry attempts
   * @param firstAttemptTime timestamp of the first attempt
   * @param lastAttemptTime timestamp of the last attempt
   * @return a retry state with the given attempts
   */
  public static RetryState of(
      List<RetryAttempt> attempts, Instant firstAttemptTime, Instant lastAttemptTime) {
    return new RetryState(
        attempts.size(), List.copyOf(attempts), firstAttemptTime, lastAttemptTime);
  }
}
