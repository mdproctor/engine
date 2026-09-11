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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Optional scheduled job that periodically replays PENDING_REVIEW dead-letter entries. Disabled by
 * default ({@code casehub.dlq.auto-replay.enabled=false}).
 *
 * <p>Config:
 *
 * <ul>
 *   <li>{@code casehub.dlq.auto-replay.enabled} (default: false)
 *   <li>{@code casehub.dlq.auto-replay.interval} (default: PT30M, ISO-8601 duration)
 *   <li>{@code casehub.dlq.auto-replay.delays} (default: PT30M,PT2H,PT8H — comma-separated ISO-8601
 *       durations)
 *   <li>{@code casehub.dlq.auto-replay.max-attempts} (default: 3)
 * </ul>
 */
public class DeadLetterAutoReplayJob {

  private static final Logger LOG = Logger.getLogger(DeadLetterAutoReplayJob.class);

  private final DeadLetterQueue deadLetterQueue;
  private final DeadLetterReplayService replayService;
  private final boolean enabled;
  private final int maxAttempts;
  private final List<Duration> delays;

  public DeadLetterAutoReplayJob(
      DeadLetterQueue deadLetterQueue,
      DeadLetterReplayService replayService,
      boolean enabled,
      int maxAttempts,
      List<Duration> delays) {
    this.deadLetterQueue = deadLetterQueue;
    this.replayService = replayService;
    this.enabled = enabled;
    this.maxAttempts = maxAttempts;
    this.delays = delays;
  }

  /**
   * Scheduled scan. Iterates all PENDING_REVIEW dead-letter entries and replays those that are
   * eligible based on attempt count and back-off delay.
   *
   * <p>Runs at the interval configured via {@code casehub.dlq.auto-replay.interval} (default 30m).
   * The scan is a no-op when {@code casehub.dlq.auto-replay.enabled=false}.
   */
  public void scan() {
    if (!enabled) {
      return;
    }
    runEligibleReplays();
  }

  void runEligibleReplays() {
    List<DeadLetterEntry> eligible =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW)).stream()
            .filter(e -> isEligible(e, maxAttempts, delays))
            .toList();

    if (eligible.isEmpty()) {
      LOG.debug("DLQ auto-replay: no eligible entries");
      return;
    }

    LOG.infof("DLQ auto-replay: attempting %d entries", eligible.size());
    for (DeadLetterEntry entry : eligible) {
      Optional<DeadLetterEntry> result = replayService.replay(entry.deadLetterId());
      if (result.isEmpty() && entry.replayAttempts() >= maxAttempts) {
        LOG.warnf(
            "DLQ auto-replay: entry %s reached max-attempts (%d) — manual triage required",
            entry.deadLetterId(), maxAttempts);
      }
    }
  }

  /**
   * Returns true if the entry is eligible for auto-replay: PENDING_REVIEW, below max-attempts, and
   * sufficient time has passed since the last attempt.
   */
  static boolean isEligible(DeadLetterEntry entry, int maxAttempts, List<Duration> delays) {
    if (entry.status() != DeadLetterStatus.PENDING_REVIEW) return false;
    if (entry.replayAttempts() >= maxAttempts) return false;

    int attemptIndex = entry.replayAttempts();
    if (attemptIndex >= delays.size()) return false;

    Duration requiredDelay = delays.get(attemptIndex);
    Instant baseline =
        entry.lastReplayAttemptAt() != null ? entry.lastReplayAttemptAt() : entry.arrivedAt();
    return baseline.plus(requiredDelay).isBefore(Instant.now());
  }
}
