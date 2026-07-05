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

import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.Objects;
import java.util.UUID;

/**
 * @param triggerChannelId Qhorus channel ID of the COMMAND that caused this context change, or
 *     null. Threaded through to ProvisionContext by CaseContextChangedEventHandler so provisioners
 *     can establish causal lineage. Refs engine#231.
 * @param triggerCorrelationId Qhorus correlationId of the triggering COMMAND, or null.
 * @param signalId Settlement tracking ID for {@code signalAndAwait()}, or null. Threaded through to
 *     WorkerScheduleEvent so each dispatched worker can notify the tracker on completion. Refs
 *     engine#483.
 */
public record CaseContextChangedEvent(
    CaseInstance instance,
    CaseContext contextSnapshot,
    String changedLayer,
    String triggerChannelId,
    String triggerCorrelationId,
    UUID signalId) {

  public CaseContextChangedEvent {
    instance = Objects.requireNonNull(instance, "instance cannot be null");
    contextSnapshot = Objects.requireNonNull(contextSnapshot, "contextSnapshot cannot be null");
    // changedLayer, triggerChannelId, triggerCorrelationId, signalId may be null
  }

  /** Convenience constructor for context changes not triggered by a Qhorus COMMAND. */
  public CaseContextChangedEvent(
      CaseInstance instance, CaseContext contextSnapshot, String changedLayer) {
    this(instance, contextSnapshot, changedLayer, null, null, null);
  }

  /** Convenience constructor for context changes with Qhorus trigger context but no settlement. */
  public CaseContextChangedEvent(
      CaseInstance instance,
      CaseContext contextSnapshot,
      String changedLayer,
      String triggerChannelId,
      String triggerCorrelationId) {
    this(instance, contextSnapshot, changedLayer, triggerChannelId, triggerCorrelationId, null);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CaseContextChangedEvent that = (CaseContextChangedEvent) o;
    return Objects.equals(instance, that.instance)
        && Objects.equals(changedLayer, that.changedLayer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(instance, changedLayer);
  }

  @Override
  public String toString() {
    return "CaseContextChangedEvent{uuid="
        + instance.getUuid()
        + ", layer="
        + changedLayer
        + ", triggerChannelId="
        + triggerChannelId
        + ", triggerCorrelationId="
        + triggerCorrelationId
        + ", signalId="
        + signalId
        + '}';
  }
}
