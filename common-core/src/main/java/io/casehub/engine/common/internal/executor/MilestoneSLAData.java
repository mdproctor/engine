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

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Scheduler-agnostic carrier for milestone SLA timeout job data. */
public record MilestoneSLAData(UUID caseId, String milestoneName) {

  public MilestoneSLAData {
    Objects.requireNonNull(caseId, "caseId must not be null");
    Objects.requireNonNull(milestoneName, "milestoneName must not be null");
  }

  public Map<String, String> toMap() {
    return Map.of("caseId", caseId.toString(), "milestoneName", milestoneName);
  }

  public static MilestoneSLAData fromMap(Map<String, String> map) {
    return new MilestoneSLAData(UUID.fromString(map.get("caseId")), map.get("milestoneName"));
  }
}
