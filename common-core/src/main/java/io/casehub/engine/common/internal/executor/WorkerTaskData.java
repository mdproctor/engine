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
import java.util.UUID;

/**
 * Scheduler-agnostic carrier for worker execution job data. Replaces the Quartz-specific
 * WorkerRetryContext with a record that any scheduler backend can serialise.
 */
public record WorkerTaskData(
    Long eventLogId,
    String inputDataHash,
    UUID caseId,
    String workerId,
    String tenancyId,
    String bindingName,
    UUID signalId) {

  public WorkerTaskData withBindingName(String bindingName) {
    return new WorkerTaskData(
        eventLogId, inputDataHash, caseId, workerId, tenancyId, bindingName, signalId);
  }

  public WorkerTaskData withSignalId(UUID signalId) {
    return new WorkerTaskData(
        eventLogId, inputDataHash, caseId, workerId, tenancyId, bindingName, signalId);
  }

  public Map<String, String> toMap() {
    var map = new HashMap<String, String>();
    map.put("eventLogId", String.valueOf(eventLogId));
    map.put("inputDataHash", inputDataHash);
    map.put("caseId", caseId.toString());
    map.put("workerId", workerId);
    map.put("tenancyId", tenancyId);
    if (bindingName != null) map.put("bindingName", bindingName);
    if (signalId != null) map.put("signalId", signalId.toString());
    return Map.copyOf(map);
  }

  public static WorkerTaskData fromMap(Map<String, String> map) {
    return new WorkerTaskData(
        Long.parseLong(map.get("eventLogId")),
        map.get("inputDataHash"),
        UUID.fromString(map.get("caseId")),
        map.get("workerId"),
        map.get("tenancyId"),
        map.get("bindingName"),
        map.containsKey("signalId") ? UUID.fromString(map.get("signalId")) : null);
  }
}
