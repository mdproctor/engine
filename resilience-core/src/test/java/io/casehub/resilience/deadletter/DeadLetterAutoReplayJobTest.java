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
package io.casehub.resilience.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.RetryState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeadLetterAutoReplayJobTest {

  @Test
  void isEligible_newEntry_firstDelayZero_returnsTrue() {
    DeadLetterQueue queue = new DeadLetterQueue();
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w", "h", Map.of(), RetryState.empty());
    assertThat(
            DeadLetterAutoReplayJob.isEligible(
                entry, 3, List.of(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(8))))
        .isTrue();
  }

  @Test
  void isEligible_afterFirstAttempt_secondDelayNotElapsed_returnsFalse() {
    DeadLetterQueue queue = new DeadLetterQueue();
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w", "h", Map.of(), RetryState.empty());
    entry.incrementReplayAttempts(); // replayAttempts=1, lastAttemptAt=now
    assertThat(
            DeadLetterAutoReplayJob.isEligible(
                entry, 3, List.of(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(8))))
        .isFalse();
  }

  @Test
  void isEligible_maxAttemptsReached_returnsFalse() {
    DeadLetterQueue queue = new DeadLetterQueue();
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w", "h", Map.of(), RetryState.empty());
    entry.incrementReplayAttempts();
    entry.incrementReplayAttempts();
    entry.incrementReplayAttempts(); // 3 attempts
    assertThat(
            DeadLetterAutoReplayJob.isEligible(
                entry, 3, List.of(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(8))))
        .isFalse();
  }

  @Test
  void isEligible_nonPendingStatus_returnsFalse() {
    DeadLetterQueue queue = new DeadLetterQueue();
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w", "h", Map.of(), RetryState.empty());
    queue.markReplayed(entry.deadLetterId());
    assertThat(DeadLetterAutoReplayJob.isEligible(entry, 3, List.of(Duration.ZERO))).isFalse();
  }

  @Test
  void runEligibleReplays_callsReplayOnEligibleEntries() {
    DeadLetterQueue queue = new DeadLetterQueue();
    DeadLetterReplayService replayService = mock(DeadLetterReplayService.class);
    DeadLetterAutoReplayJob job =
        new DeadLetterAutoReplayJob(
            queue, replayService, 3, List.of(Duration.ZERO, Duration.ofHours(1)));

    DeadLetterEntry e1 = queue.add(UUID.randomUUID(), "w1", "h1", Map.of(), RetryState.empty());
    DeadLetterEntry e2 = queue.add(UUID.randomUUID(), "w2", "h2", Map.of(), RetryState.empty());

    when(replayService.replay(e1.deadLetterId())).thenReturn(Optional.of(e1));
    when(replayService.replay(e2.deadLetterId())).thenReturn(Optional.of(e2));

    job.runEligibleReplays();

    verify(replayService).replay(e1.deadLetterId());
    verify(replayService).replay(e2.deadLetterId());
  }
}
