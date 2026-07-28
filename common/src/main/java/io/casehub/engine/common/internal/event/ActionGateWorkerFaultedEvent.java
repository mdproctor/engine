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
 * Published on {@link EventBusAddresses#ACTION_GATE_WORKER_FAULTED} by {@code
 * ActionGateRejectedHandler} and {@code ActionGateExpiredHandler} in the engine runtime after a
 * gate is resolved negatively.
 *
 * <p>Consumed by the blackboard module ({@code ActionGateRejectedPlanItemHandler}, {@code
 * ActionGateExpiredPlanItemHandler}) to mark the associated PlanItem FAULTED, enabling {@code
 * CompoundCompletionEvaluator} to fire. This is distinct from {@link WorkerRetriesExhaustedEvent}
 * which also faults the {@code CaseInstance} state — gate faults must leave the case RUNNING so the
 * rejection binding can react.
 *
 * @param caseId the case this gate belongs to
 * @param tenancyId the tenant owning the case
 * @param workerId the worker that was faulted
 * @param idempotency the idempotency key for the faulted execution
 */
public record ActionGateWorkerFaultedEvent(
    UUID caseId, String tenancyId, String workerId, String idempotency) {}
