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

import io.casehub.api.spi.routing.EscalationReason;
import java.util.UUID;

/**
 * Published when agent routing cannot proceed automatically and human oversight is required. The
 * {@link EscalationReason} indicates whether the trigger was a borderline stalemate or a pool with
 * no trust-qualified agents.
 *
 * @param caseId the case where routing escalation occurred
 * @param tenancyId the tenant owning the case
 * @param capabilityName the capability that could not be routed
 * @param bindingName the binding that triggered the escalation
 * @param reason the escalation reason
 */
public record AgentRoutingEscalationEvent(
    UUID caseId,
    String tenancyId,
    String capabilityName,
    String bindingName,
    EscalationReason reason) {}
