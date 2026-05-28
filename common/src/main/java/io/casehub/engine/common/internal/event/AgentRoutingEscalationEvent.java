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
 * Published to {@link EventBusAddresses#AGENT_ROUTING_ESCALATION} when all trust-eligible agent
 * candidates for a capability are borderline (trust score within {@code borderlineMargin} of {@code
 * threshold}). Signals that human oversight is required to decide which agent should handle the
 * capability for this case.
 *
 * <p>Handler: {@code AgentRoutingEscalationHandler} posts a QUERY to the case's oversight channel
 * so a human supervisor can make the routing decision.
 *
 * <p>PlanItem state during escalation: stays PENDING. Response handling (COMMAND → re-trigger
 * routing) tracked in engine#383.
 */
public record AgentRoutingEscalationEvent(UUID caseId, String capabilityName, String bindingName) {}
