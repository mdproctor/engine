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

import io.casehub.api.model.RetryState;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A record of a worker execution that exhausted all retry attempts. Stores the full context needed
 * to replay the work or diagnose the failure: case ID, worker identity, original input, and current
 * lifecycle status.
 *
 * <p>Status transitions: {@link DeadLetterStatus#PENDING_REVIEW} → {@link
 * DeadLetterStatus#REPLAYED} or {@link DeadLetterStatus#DISCARDED}.
 */
public final class DeadLetterEntry {

  private final String deadLetterId;
  private final UUID caseId;
  private final String workerId;
  private final String idempotencyHash;
  private final Map<String, Object> inputContext;
  private final RetryState retryState;
  private final Instant arrivedAt;
  private volatile DeadLetterStatus status;
  private volatile int replayAttempts = 0;
  private volatile Instant lastReplayAttemptAt = null;

  DeadLetterEntry(
      String deadLetterId,
      UUID caseId,
      String workerId,
      String idempotencyHash,
      Map<String, Object> inputContext,
      RetryState retryState) {
    this.deadLetterId = deadLetterId;
    this.caseId = caseId;
    this.workerId = workerId;
    this.idempotencyHash = idempotencyHash;
    this.inputContext = Map.copyOf(inputContext);
    this.retryState = retryState != null ? retryState : RetryState.empty();
    this.arrivedAt = Instant.now();
    this.status = DeadLetterStatus.PENDING_REVIEW;
  }

  public String deadLetterId() {
    return deadLetterId;
  }

  public UUID caseId() {
    return caseId;
  }

  public String workerId() {
    return workerId;
  }

  public String idempotencyHash() {
    return idempotencyHash;
  }

  public Map<String, Object> inputContext() {
    return inputContext;
  }

  public Instant arrivedAt() {
    return arrivedAt;
  }

  public DeadLetterStatus status() {
    return status;
  }

  void setStatus(DeadLetterStatus status) {
    this.status = status;
  }

  public int replayAttempts() {
    return replayAttempts;
  }

  public Instant lastReplayAttemptAt() {
    return lastReplayAttemptAt;
  }

  public RetryState retryState() {
    return retryState;
  }

  void incrementReplayAttempts() {
    replayAttempts++;
    lastReplayAttemptAt = Instant.now();
  }
}
