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
package io.casehub.engine.common.spi.event;

import java.util.UUID;

/**
 * CDI event fired after a PlanItem is marked {@code COMPLETED} (successful outcome only). Fired via
 * {@code Event.fireAsync()} from the blackboard module's PlanItemCompletionHandler.
 *
 * <p><strong>Contract:</strong> This event fires only on {@code COMPLETED} terminal state. Faulted,
 * rejected, and cancelled PlanItems do NOT emit this event — observers must not assume every
 * terminal transition produces one.
 *
 * <p>By the time observers receive this event, the specific {@code planItemId} is guaranteed to
 * have COMPLETED status in the registry — no polling required.
 *
 * @param caseId the case the PlanItem belongs to
 * @param planItemId the exact PlanItem id that just completed
 * @param trackingKey the external identifier that triggered completion (workerName for
 *     CapabilityTarget; childCaseId string for SubCaseTarget; bindingName when the binding-name
 *     lookup path is used)
 * @param tenancyId the tenant that owns the case
 */
public record PlanItemCompletedEvent(
    UUID caseId, String planItemId, String trackingKey, String tenancyId) {}
