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
package io.casehub.engine.common.internal.event;

import java.util.Map;
import java.util.UUID;

/**
 * Published on {@link EventBusAddresses#SIGNAL_RECEIVED} when {@code signal(UUID, Map)} is called.
 * Applies all updates atomically via {@code setAll()}.
 *
 * <p>{@code signalId} is non-null only when called from {@code signalAndAwait()} — it tags the
 * context change so settlement can be tracked across worker execution completions.
 *
 * <p>Refs casehubio/engine#483.
 *
 * @param triggerChannelId Qhorus channel ID of the COMMAND that caused this signal, or null.
 * @param triggerCorrelationId Qhorus correlationId of the triggering COMMAND, or null.
 * @param signalId Settlement tracking ID for {@code signalAndAwait()}, or null.
 */
public record BulkSignalReceivedEvent(
    UUID caseId,
    Map<String, Object> updates,
    String triggerChannelId,
    String triggerCorrelationId,
    UUID signalId) {

  public BulkSignalReceivedEvent {
    if (caseId == null) {
      throw new IllegalArgumentException("caseId cannot be null");
    }
    if (updates == null) {
      throw new IllegalArgumentException("updates cannot be null");
    }
  }

  /** Convenience constructor for signals not triggered by a Qhorus COMMAND and not awaiting. */
  public BulkSignalReceivedEvent(UUID caseId, Map<String, Object> updates) {
    this(caseId, updates, null, null, null);
  }

  @Override
  public String toString() {
    return "BulkSignalReceivedEvent{"
        + "caseId="
        + caseId
        + ", updates="
        + updates.keySet()
        + ", triggerChannelId="
        + triggerChannelId
        + ", triggerCorrelationId="
        + triggerCorrelationId
        + ", signalId="
        + signalId
        + '}';
  }
}
