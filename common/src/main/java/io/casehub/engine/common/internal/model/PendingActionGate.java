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
package io.casehub.engine.common.internal.model;

import io.casehub.worker.api.PlannedAction;
import java.util.Map;

/**
 * Operational state for an in-flight action gate stored on {@link CaseInstance}.
 *
 * <p>Set when a worker declares a {@link PlannedAction} and the engine's {@link
 * io.casehub.blocks.oversight.ActionRiskClassifier} returns {@link
 * io.casehub.blocks.oversight.RiskDecision.GateRequired}. Cleared by the gate resolution handlers
 * ({@code ActionGateApprovedHandler}, {@code ActionGateRejectedHandler}, {@code
 * ActionGateExpiredHandler}) after they finish processing.
 *
 * <p>{@code gateId} is the EventLog entry id of the {@code ACTION_GATE_PENDING} entry. It is
 * embedded in the WorkItem callerRef as {@code "case:{caseId}/gate:{gateId}"} and used to correlate
 * the gate back to its deferred output on resolution.
 *
 * <p>{@link io.casehub.api.model.Worker} is intentionally absent — lambdas are not
 * Jackson-serializable. The approved handler retrieves the {@code Worker} at resolution time via
 * {@code CaseDefinitionRegistry}.
 */
public record PendingActionGate(
    long gateId,
    String workerId,
    String idempotency,
    Map<String, Object> deferredOutput,
    PlannedAction plannedAction) {}
