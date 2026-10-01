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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.api.context.CaseContext;
import io.casehub.engine.internal.context.CaseContextImpl;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseCompletionTrackerTest {

  private CaseCompletionTracker tracker;

  @BeforeEach
  void setUp() {
    tracker = new CaseCompletionTracker();
  }

  @Test
  void register_thenComplete_resolvesFuture() throws Exception {
    UUID caseId = UUID.randomUUID();
    CompletableFuture<CaseContext> future = tracker.register(caseId);

    CaseContext mockContext = new CaseContextImpl();
    tracker.complete(caseId, mockContext);

    CaseContext result = future.get(1, TimeUnit.SECONDS);
    assertSame(mockContext, result);
  }

  @Test
  void complete_withoutRegister_isNoOp() {
    UUID caseId = UUID.randomUUID();
    CaseContext mockContext = new CaseContextImpl();
    assertDoesNotThrow(() -> tracker.complete(caseId, mockContext));
  }

  @Test
  void register_thenCompleteExceptionally_failsFuture() {
    UUID caseId = UUID.randomUUID();
    CompletableFuture<CaseContext> future = tracker.register(caseId);

    tracker.completeExceptionally(caseId, new RuntimeException("case faulted"));

    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    assertEquals("case faulted", ex.getCause().getMessage());
  }

  @Test
  void register_thenTimeout_throwsTimeoutException() {
    UUID caseId = UUID.randomUUID();
    CompletableFuture<CaseContext> future = tracker.register(caseId);

    assertThrows(TimeoutException.class, () -> future.get(50, TimeUnit.MILLISECONDS));
  }

  @Test
  void remove_cleansUpEntry() {
    UUID caseId = UUID.randomUUID();
    tracker.register(caseId);
    tracker.remove(caseId);

    // A second register creates a new future (not the removed one)
    CompletableFuture<CaseContext> future2 = tracker.register(caseId);
    assertFalse(future2.isDone());
  }

  @Test
  void register_idempotent_returnsSameFuture() {
    UUID caseId = UUID.randomUUID();
    CompletableFuture<CaseContext> f1 = tracker.register(caseId);
    CompletableFuture<CaseContext> f2 = tracker.register(caseId);
    assertSame(f1, f2);
  }
}
