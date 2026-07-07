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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.RetryState.RetryAttempt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryStateTest {

  @Test
  void empty_returnsZeroAttemptsAndNullTimestamps() {
    RetryState state = RetryState.empty();

    assertThat(state.attemptCount()).isZero();
    assertThat(state.attempts()).isEmpty();
    assertThat(state.firstAttemptTime()).isNull();
    assertThat(state.lastAttemptTime()).isNull();
  }

  @Test
  void of_populatesAllFields() {
    Instant first = Instant.parse("2026-07-07T10:00:00Z");
    Instant second = Instant.parse("2026-07-07T10:01:00Z");
    Instant third = Instant.parse("2026-07-07T10:02:00Z");

    RetryAttempt attempt1 =
        new RetryAttempt(first, "Connection timeout", Duration.ofSeconds(30), false);
    RetryAttempt attempt2 =
        new RetryAttempt(second, "Connection timeout", Duration.ofSeconds(30), false);
    RetryAttempt attempt3 = new RetryAttempt(third, null, Duration.ofSeconds(10), true);

    RetryState state = RetryState.of(List.of(attempt1, attempt2, attempt3), first, third);

    assertThat(state.attemptCount()).isEqualTo(3);
    assertThat(state.attempts()).containsExactly(attempt1, attempt2, attempt3);
    assertThat(state.firstAttemptTime()).isEqualTo(first);
    assertThat(state.lastAttemptTime()).isEqualTo(third);
  }

  @Test
  void of_createsImmutableCopy() {
    Instant now = Instant.now();
    RetryAttempt attempt = new RetryAttempt(now, "error", Duration.ofSeconds(5), false);
    List<RetryAttempt> mutableList = new java.util.ArrayList<>(List.of(attempt));

    RetryState state = RetryState.of(mutableList, now, now);

    // Mutating the original list should not affect the state
    mutableList.clear();

    assertThat(state.attemptCount()).isEqualTo(1);
    assertThat(state.attempts()).containsExactly(attempt);
  }

  @Test
  void retryAttempt_recordsSuccessAndFailure() {
    Instant now = Instant.now();

    RetryAttempt failure =
        new RetryAttempt(now, "Database unavailable", Duration.ofSeconds(20), false);
    assertThat(failure.succeeded()).isFalse();
    assertThat(failure.errorMessage()).isEqualTo("Database unavailable");

    RetryAttempt success = new RetryAttempt(now, null, Duration.ofSeconds(5), true);
    assertThat(success.succeeded()).isTrue();
    assertThat(success.errorMessage()).isNull();
  }
}
