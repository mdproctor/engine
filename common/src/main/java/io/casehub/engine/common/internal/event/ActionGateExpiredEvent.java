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
 * Published on {@link EventBusAddresses#ACTION_GATE_EXPIRED} by {@code ActionGateCompletionApplier}
 * (work-adapter) when a gate WorkItem expires before approval.
 *
 * <p>Consumed by {@code ActionGateExpiredHandler} in the engine runtime (writes {@code
 * actionGateExpired} signal, calls {@code workerStatusListener}, fires CONTEXT_CHANGED) and by
 * {@code ActionGateExpiredPlanItemHandler} in the blackboard module (marks PlanItem FAULTED).
 *
 * @param caseId the case this gate belongs to
 * @param tenancyId the tenant owning the case
 * @param gateId the gate identifier
 */
public record ActionGateExpiredEvent(UUID caseId, String tenancyId, long gateId) {}
