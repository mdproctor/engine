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
package io.casehub.resilience.poison;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Sliding-window circuit breaker that quarantines workers that fail too frequently.
 *
 * <p>Tracks failure timestamps per worker within a configurable time window. When the number of
 * failures within the window reaches the threshold, the worker is quarantined for a cool-down
 * period. Quarantined workers should be skipped by the scheduler — no further executions are
 * attempted until the quarantine expires or is manually released.
 *
 * <p>Thread-safe. All state is in-memory.
 */
@ApplicationScoped
public class PoisonPillDetector {

  private static final Logger LOG = Logger.getLogger(PoisonPillDetector.class);

  private final int failureThreshold;
  private final Duration failureWindow;
  private final Duration quarantineDuration;

  /** Sliding window of failure timestamps per worker. */
  private final Map<String, Deque<Instant>> failureWindows = new ConcurrentHashMap<>();

  /** Quarantine expiry times per worker. Absent = not quarantined. */
  private final Map<String, Instant> quarantinedUntil = new ConcurrentHashMap<>();

  /** CDI no-arg constructor using config properties. */
  PoisonPillDetector(
      @ConfigProperty(name = "casehub.resilience.poison-pill.threshold", defaultValue = "5")
          int failureThreshold,
      @ConfigProperty(name = "casehub.resilience.poison-pill.window", defaultValue = "PT10M")
          Duration failureWindow,
      @ConfigProperty(name = "casehub.resilience.poison-pill.quarantine", defaultValue = "PT30M")
          Duration quarantineDuration) {
    this.failureThreshold = failureThreshold;
    this.failureWindow = failureWindow;
    this.quarantineDuration = quarantineDuration;
  }

  /**
   * Records a failure for the given worker. If the number of failures within the sliding window
   * reaches the threshold, the worker is quarantined.
   *
   * @param workerId the name of the failing worker
   */
  public synchronized void recordFailure(String workerId) {
    Instant now = Instant.now();
    Deque<Instant> window = failureWindows.computeIfAbsent(workerId, k -> new ArrayDeque<>());

    window.addLast(now);
    evictExpired(window, now);

    if (window.size() >= failureThreshold) {
      Instant until = now.plus(quarantineDuration);
      quarantinedUntil.put(workerId, until);
      LOG.warnf(
          "Worker quarantined: workerId=%s, failures=%d, until=%s", workerId, window.size(), until);
    }
  }

  /**
   * Returns {@code true} if the worker is currently quarantined. Expired quarantines are
   * automatically cleared.
   *
   * @param workerId the worker to check
   */
  public boolean isQuarantined(String workerId) {
    Instant expiry = quarantinedUntil.get(workerId);
    if (expiry == null) {
      return false;
    }
    if (Instant.now().isAfter(expiry)) {
      quarantinedUntil.remove(workerId);
      return false;
    }
    return true;
  }

  /**
   * Manually releases a quarantine and resets the failure window for the given worker. Useful for
   * operator intervention after a fix is deployed.
   *
   * @param workerId the worker to release
   */
  public synchronized void release(String workerId) {
    quarantinedUntil.remove(workerId);
    failureWindows.remove(workerId);
    LOG.infof("Quarantine released for workerId=%s", workerId);
  }

  /**
   * Returns the set of currently quarantined worker IDs. Expired quarantines are excluded (lazily
   * cleaned on read).
   */
  public Set<String> quarantinedWorkers() {
    Instant now = Instant.now();
    // Evict expired entries first
    quarantinedUntil.entrySet().removeIf(e -> now.isAfter(e.getValue()));
    return Collections.unmodifiableSet(quarantinedUntil.keySet());
  }

  private void evictExpired(Deque<Instant> window, Instant now) {
    Instant cutoff = now.minus(failureWindow);
    while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
      window.pollFirst();
    }
  }
}
