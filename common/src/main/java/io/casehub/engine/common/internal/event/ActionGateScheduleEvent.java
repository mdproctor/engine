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

import io.casehub.api.spi.RiskDecision;
import io.casehub.worker.api.PlannedAction;
import java.util.Set;
import java.util.UUID;

/**
 * Published on {@link EventBusAddresses#ACTION_GATE_SCHEDULE} when the engine determines a worker's
 * action must be gated for human approval.
 *
 * <p>{@code gateId} is the EventLog entry id of the {@code ACTION_GATE_PENDING} entry — it becomes
 * the stable key linking the gate to its WorkItem via callerRef {@code
 * "case:{caseId}/gate:{gateId}"}.
 *
 * <p>Consumed by {@code ActionGateWorkItemHandler} in {@code casehub-engine-work-adapter}, which
 * creates the WorkItem. If work-adapter is absent, this event fires with no handler and the case
 * stalls.
 */
public record ActionGateScheduleEvent(
    UUID caseId,
    String tenancyId,
    long gateId,
    PlannedAction plannedAction,
    RiskDecision.GateRequired gateRequired,
    Set<String> resolvedCandidateGroups) {}
