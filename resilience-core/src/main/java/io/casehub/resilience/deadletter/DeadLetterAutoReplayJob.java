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

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
@ApplicationScoped
public class DeadLetterAutoReplayJob {

  private static final Logger LOG = Logger.getLogger(DeadLetterAutoReplayJob.class);

  @Inject DeadLetterQueue deadLetterQueue;
  @Inject DeadLetterReplayService replayService;

  @ConfigProperty(name = "casehub.dlq.auto-replay.enabled", defaultValue = "false")
  boolean enabled;

  @ConfigProperty(name = "casehub.dlq.auto-replay.max-attempts", defaultValue = "3")
  int maxAttempts;

  @ConfigProperty(name = "casehub.dlq.auto-replay.delays", defaultValue = "PT30M,PT2H,PT8H")
  List<Duration> delays;

  /** Non-CDI constructor for unit tests. */
  DeadLetterAutoReplayJob(
      DeadLetterQueue deadLetterQueue,
      DeadLetterReplayService replayService,
      int maxAttempts,
      List<Duration> delays) {
    this.deadLetterQueue = deadLetterQueue;
    this.replayService = replayService;
    this.maxAttempts = maxAttempts;
    this.delays = delays;
  }

  /** Required by CDI. */
  DeadLetterAutoReplayJob() {}

  /**
   * Scheduled scan. Iterates all PENDING_REVIEW dead-letter entries and replays those that are
   * eligible based on attempt count and back-off delay.
   *
   * <p>Runs at the interval configured via {@code casehub.dlq.auto-replay.interval} (default 30m).
   * The scan is a no-op when {@code casehub.dlq.auto-replay.enabled=false}.
   */
  @Scheduled(every = "${casehub.dlq.auto-replay.interval:PT30M}")
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
