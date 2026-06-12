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
 * CDI event fired after a PlanItem is marked {@code REJECTED}. Fired via {@code Event.fireAsync()}
 * from the work-adapter when a WorkItem is rejected by a human participant.
 *
 * <p>Rejection is an intentional human refusal — distinct from fault (system failure) and
 * cancellation (administrative). Trust scoring dimensions like scope-calibration and
 * false-positive-rate use this event to attribute non-COMPLETED outcomes.
 *
 * @param caseId the case the PlanItem belongs to
 * @param planItemId the exact PlanItem id that was rejected
 * @param bindingName the binding name associated with the rejected WorkItem
 * @param tenancyId the tenant that owns the case
 */
public record PlanItemRejectedEvent(
    UUID caseId, String planItemId, String bindingName, String tenancyId) {}
