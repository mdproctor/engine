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
package io.casehub.engine.internal.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalSettlementTrackerTest {

  private SignalSettlementTracker tracker;

  @BeforeEach
  void setUp() {
    tracker = new SignalSettlementTracker();
  }

  @Test
  void zeroWorkers_resolvesOnFullyDispatched() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.markFullyDispatched(signalId);

    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void oneWorker_resolvesOnCompletion() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.incrementExpected(signalId);
    tracker.markFullyDispatched(signalId);
    assertFalse(future.isDone());

    tracker.recordCompletion(signalId);
    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void completionBeforeFullyDispatched_resolvesOnDispatchMark() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.incrementExpected(signalId);
    tracker.recordCompletion(signalId);
    assertFalse(future.isDone());

    tracker.markFullyDispatched(signalId);
    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void multipleWorkers_allMustComplete() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.incrementExpected(signalId);
    tracker.incrementExpected(signalId);
    tracker.incrementExpected(signalId);
    tracker.markFullyDispatched(signalId);

    tracker.recordCompletion(signalId);
    assertFalse(future.isDone());
    tracker.recordCompletion(signalId);
    assertFalse(future.isDone());
    tracker.recordCompletion(signalId);
    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void resolvedEntry_isCleanedUp() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);
    tracker.markFullyDispatched(signalId);
    assertNull(future.get(1, TimeUnit.SECONDS));

    assertNull(tracker.getFuture(signalId));
  }
}
