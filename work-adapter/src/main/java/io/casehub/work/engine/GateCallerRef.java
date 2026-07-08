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
 * CallerRef for WorkItems created for an action gate.
 *
 * <p>Format: {@code "case:{caseId}/gate:{gateId}"}
 *
 * <p>{@code gateId} is the EventLog entry id of the {@code ACTION_GATE_PENDING} record, used to
 * correlate the gate's deferred output at resolution time.
 *
 * <p>Gate callerRefs bypass the blackboard guard in {@code WorkItemLifecycleAdapter} — gates have
 * no associated PlanItem. The routing check must happen before the blackboard guard. Refs
 * engine#402.
 */
public record GateCallerRef(UUID caseId, long gateId) implements CallerRef {

  public static String encode(final UUID caseId, final long gateId) {
    return "case:" + caseId + "/gate:" + gateId;
  }
}
