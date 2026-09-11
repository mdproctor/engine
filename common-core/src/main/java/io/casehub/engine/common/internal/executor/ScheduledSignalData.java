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
package io.casehub.engine.common.internal.executor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Scheduler-agnostic carrier for scheduled signal trigger job data. Signal triggers apply a payload
 * to the case context instead of dispatching a worker.
 */
public record ScheduledSignalData(
    UUID caseId, String bindingName, String signalPayload, boolean hasCondition) {

  public ScheduledSignalData {
    Objects.requireNonNull(caseId, "caseId must not be null");
    Objects.requireNonNull(bindingName, "bindingName must not be null");
    Objects.requireNonNull(signalPayload, "signalPayload must not be null");
  }

  public Map<String, String> toMap() {
    var map = new HashMap<String, String>();
    map.put("caseId", caseId.toString());
    map.put("bindingName", bindingName);
    map.put("signalPayload", signalPayload);
    if (hasCondition) {
      map.put("hasCondition", "true");
    }
    return Map.copyOf(map);
  }

  public static ScheduledSignalData fromMap(Map<String, String> map) {
    return new ScheduledSignalData(
        UUID.fromString(map.get("caseId")),
        map.get("bindingName"),
        map.get("signalPayload"),
        "true".equals(map.get("hasCondition")));
  }
}
