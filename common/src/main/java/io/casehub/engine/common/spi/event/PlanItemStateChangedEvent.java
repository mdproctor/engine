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

import io.casehub.engine.common.internal.model.TaskStatus;
import java.util.UUID;

/**
 * CDI event fired when a PlanItem transitions between states. Generalises the former
 * {@code PlanItemCompletedEvent}, {@code PlanItemFaultedEvent}, and {@code PlanItemRejectedEvent}
 * into a single event carrying both the previous and new status.
 *
 * <p>Observers filter on {@code newStatus} (and optionally {@code previousStatus}) to handle
 * specific transitions. Fired via {@code Event.fireAsync()} from planning module handlers.
 *
 * @param caseId the case the PlanItem belongs to
 * @param planItemId the exact PlanItem id that transitioned
 * @param bindingName the binding associated with this plan item
 * @param previousStatus the status before the transition (null for initial PENDING creation)
 * @param newStatus the status after the transition
 * @param tenancyId the tenant that owns the case
 */
public record PlanItemStateChangedEvent(
    UUID caseId,
    String planItemId,
    String bindingName,
    TaskStatus previousStatus,
    TaskStatus newStatus,
    String tenancyId) {}
