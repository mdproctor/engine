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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests — no Quarkus, no CDI. PoisonPillDetector is a self-contained sliding-window
 * circuit breaker that must be testable without a container.
 */
class PoisonPillDetectorTest {

  /** Small threshold and window for fast tests. */
  private PoisonPillDetector detector;

  @BeforeEach
  void setUp() {
    // threshold=3, window=1 minute, quarantine=1 minute
    detector = new PoisonPillDetector(3, Duration.ofMinutes(1), Duration.ofMinutes(1));
  }

  // ---- Below threshold — not quarantined ------------------------------------

  @Test
  void belowThreshold_workerIsNotQuarantined() {
    detector.recordFailure("worker-a");
    detector.recordFailure("worker-a");

    assertThat(detector.isQuarantined("worker-a")).isFalse();
  }

  @Test
  void noFailures_workerIsNotQuarantined() {
    assertThat(detector.isQuarantined("brand-new-worker")).isFalse();
  }

  // ---- At threshold — quarantined -------------------------------------------

  @Test
  void atThreshold_workerIsQuarantined() {
    detector.recordFailure("worker-b");
    detector.recordFailure("worker-b");
    detector.recordFailure("worker-b"); // 3rd failure triggers quarantine

    assertThat(detector.isQuarantined("worker-b")).isTrue();
  }

  @Test
  void beyondThreshold_workerRemainsQuarantined() {
    for (int i = 0; i < 10; i++) {
      detector.recordFailure("worker-c");
    }
    assertThat(detector.isQuarantined("worker-c")).isTrue();
  }

  // ---- Quarantine only affects the named worker -----------------------------

  @Test
  void quarantineIsPerWorker_otherWorkersUnaffected() {
    detector.recordFailure("bad-worker");
    detector.recordFailure("bad-worker");
    detector.recordFailure("bad-worker");

    assertThat(detector.isQuarantined("bad-worker")).isTrue();
    assertThat(detector.isQuarantined("good-worker")).isFalse();
  }

  // ---- Manual release -------------------------------------------------------

  @Test
  void release_clearsQuarantine() {
    detector.recordFailure("worker-d");
    detector.recordFailure("worker-d");
    detector.recordFailure("worker-d");
    assertThat(detector.isQuarantined("worker-d")).isTrue();

    detector.release("worker-d");

    assertThat(detector.isQuarantined("worker-d")).isFalse();
  }

  @Test
  void release_unknownWorker_doesNotThrow() {
    detector.release("nonexistent"); // must not throw
  }

  @Test
  void release_resetsFailureCount() {
    // After release, two more failures should NOT re-trigger quarantine (below threshold)
    detector.recordFailure("worker-e");
    detector.recordFailure("worker-e");
    detector.recordFailure("worker-e");
    detector.release("worker-e");

    detector.recordFailure("worker-e");
    detector.recordFailure("worker-e"); // only 2 new failures

    assertThat(detector.isQuarantined("worker-e")).isFalse();
  }

  // ---- Failures outside window don't count ----------------------------------

  @Test
  void failuresOutsideWindow_doNotContributeToThreshold() throws InterruptedException {
    // Use a very short window detector
    PoisonPillDetector shortWindowDetector =
        new PoisonPillDetector(3, Duration.ofMillis(50), Duration.ofMinutes(1));

    shortWindowDetector.recordFailure("worker-f");
    shortWindowDetector.recordFailure("worker-f");
    Thread.sleep(100); // let the window expire

    // These two are within the new window — below threshold of 3
    shortWindowDetector.recordFailure("worker-f");
    shortWindowDetector.recordFailure("worker-f");

    assertThat(shortWindowDetector.isQuarantined("worker-f")).isFalse();
  }

  // ---- Quarantine expiry ----------------------------------------------------

  @Test
  void quarantine_expiresAfterCoolDown() throws InterruptedException {
    PoisonPillDetector shortQuarantineDetector =
        new PoisonPillDetector(3, Duration.ofMinutes(1), Duration.ofMillis(80));

    shortQuarantineDetector.recordFailure("worker-g");
    shortQuarantineDetector.recordFailure("worker-g");
    shortQuarantineDetector.recordFailure("worker-g");
    assertThat(shortQuarantineDetector.isQuarantined("worker-g")).isTrue();

    Thread.sleep(150); // wait for quarantine to expire

    assertThat(shortQuarantineDetector.isQuarantined("worker-g")).isFalse();
  }

  // ---- Quarantine status reporting ------------------------------------------

  @Test
  void quarantinedWorkers_listReturnsAllQuarantinedNames() {
    detector.recordFailure("slow-worker");
    detector.recordFailure("slow-worker");
    detector.recordFailure("slow-worker");

    assertThat(detector.quarantinedWorkers()).contains("slow-worker");
    assertThat(detector.quarantinedWorkers()).doesNotContain("clean-worker");
  }
}
