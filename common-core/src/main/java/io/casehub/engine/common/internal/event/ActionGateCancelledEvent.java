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
 * Published on {@link EventBusAddresses#ACTION_GATE_CANCELLED} by {@code CaseStatusChangedHandler}
 * when a case transitions to a terminal state (COMPLETED, FAULTED, CANCELLED) while a gate is
 * pending.
 *
 * <p>Consumed by {@code ActionGateCancelledHandler} in work-adapter, which cancels the orphaned
 * gate WorkItem via {@code WorkItemService}. Prevents orphaned WorkItems resolving against a dead
 * case. Cancellation is a no-op if the WorkItem has already reached a terminal state.
 *
 * @param caseId the case this gate belongs to
 * @param tenancyId the tenant owning the case
 * @param gateId the gate identifier
 */
public record ActionGateCancelledEvent(UUID caseId, String tenancyId, long gateId) {}
