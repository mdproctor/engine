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

/**
 * @param triggerChannelId Qhorus channel ID of the COMMAND that caused this context change, or
 *     null. Threaded through to ProvisionContext by CaseContextChangedEventHandler so provisioners
 *     can establish causal lineage. Refs engine#231.
 * @param triggerCorrelationId Qhorus correlationId of the triggering COMMAND, or null.
 */
public record CaseContextChangedEvent(
    CaseInstance instance,
    CaseContext contextSnapshot,
    String changedPanel,
    String triggerChannelId,
    String triggerCorrelationId) {

  public CaseContextChangedEvent {
    instance = Objects.requireNonNull(instance, "instance cannot be null");
    contextSnapshot = Objects.requireNonNull(contextSnapshot, "contextSnapshot cannot be null");
    // changedPanel, triggerChannelId, triggerCorrelationId may be null
  }

  /** Convenience constructor for context changes not triggered by a Qhorus COMMAND. */
  public CaseContextChangedEvent(
      CaseInstance instance, CaseContext contextSnapshot, String changedPanel) {
    this(instance, contextSnapshot, changedPanel, null, null);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CaseContextChangedEvent that = (CaseContextChangedEvent) o;
    return Objects.equals(instance, that.instance)
        && Objects.equals(changedPanel, that.changedPanel);
  }

  @Override
  public int hashCode() {
    return Objects.hash(instance, changedPanel);
  }

  @Override
  public String toString() {
    return "CaseContextChangedEvent{uuid="
        + instance.getUuid()
        + ", panel="
        + changedPanel
        + ", triggerChannelId="
        + triggerChannelId
        + ", triggerCorrelationId="
        + triggerCorrelationId
        + '}';
  }
}
