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
package io.casehub.blackboard.event;

import java.util.UUID;

/**
 * CDI event fired after a {@link io.casehub.blackboard.plan.PlanItem} is marked COMPLETED in the
 * BlackboardRegistry. Fired via {@code Event.fireAsync()} from {@link
 * io.casehub.blackboard.handler.PlanItemCompletionHandler}.
 *
 * <p>By the time observers receive this event, the specific {@code planItemId} is guaranteed to
 * have COMPLETED status in the registry — no polling required.
 *
 * <p>Use {@code planItemId} (not a subsequent {@code getPlanItemId()} lookup) to identify the
 * completed item, since the completion index may be overwritten by a re-triggered PlanItem for the
 * same worker.
 *
 * @param caseId the case the PlanItem belongs to
 * @param planItemId the exact PlanItem id that just completed
 * @param trackingKey the external identifier that triggered completion (workerName for
 *     CapabilityTarget; childCaseId string for SubCaseTarget)
 */
public record PlanItemCompletedEvent(UUID caseId, String planItemId, String trackingKey) {}
