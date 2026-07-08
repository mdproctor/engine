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
package io.casehub.work.engine;

import java.util.UUID;

/**
 * CallerRef for WorkItems created from humanTask YAML bindings.
 *
 * <p>Format: {@code "case:{caseId}/pi:{planItemId}"}
 *
 * <p>Routes WorkItem lifecycle events back to the associated PlanItem in the blackboard. Refs
 * casehubio/work#136, engine#245.
 */
public record PlanItemCallerRef(UUID caseId, String planItemId) implements CallerRef {

  public static String encode(final UUID caseId, final String planItemId) {
    return "case:" + caseId + "/pi:" + planItemId;
  }
}
