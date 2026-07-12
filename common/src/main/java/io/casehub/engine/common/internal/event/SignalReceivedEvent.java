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

import java.util.UUID;

/**
 * @param tenancyId Tenant that owns this case — explicit for correct RLS enforcement.
 * @param triggerChannelId Qhorus channel ID of the COMMAND that caused this signal, or null.
 *     Threaded through to ProvisionContext so provisioners can establish causal lineage. Refs
 *     engine#231.
 * @param triggerCorrelationId Qhorus correlationId of the triggering COMMAND, or null.
 */
public record SignalReceivedEvent(
    UUID caseId,
    String tenancyId,
    String path,
    Object value,
    String triggerChannelId,
    String triggerCorrelationId) {

  public SignalReceivedEvent {
    if (caseId == null) {
      throw new IllegalArgumentException("caseId cannot be null");
    }
    if (tenancyId == null) {
      throw new IllegalArgumentException("tenancyId cannot be null");
    }
    if (path == null) {
      throw new IllegalArgumentException("path cannot be null");
    }
  }

  /** Convenience constructor for signals not triggered by a Qhorus COMMAND. */
  public SignalReceivedEvent(UUID caseId, String tenancyId, String path, Object value) {
    this(caseId, tenancyId, path, value, null, null);
  }

  @Override
  public String toString() {
    return "SignalReceivedEvent{"
        + "caseId="
        + caseId
        + ", tenancyId="
        + tenancyId
        + ", path='"
        + path
        + '\''
        + ", value="
        + value
        + ", triggerChannelId="
        + triggerChannelId
        + ", triggerCorrelationId="
        + triggerCorrelationId
        + '}';
  }
}
