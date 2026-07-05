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
package io.casehub.api.spi.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Routing context passed to {@link AgentRoutingStrategy#select}.
 *
 * <p>{@code caseId} is included per the {@code spi-case-id-parameter.md} protocol — it enables a
 * future {@code PerCaseDynamicAgentRoutingStrategy} to dispatch per-case without call-site changes.
 *
 * <p>{@code caseContext} is the full case context map serialized as a {@link JsonNode}. Semantic
 * routing strategies (e.g. {@code SemanticAgentRoutingStrategy}) extract a text summary via a
 * configurable JQ expression before embedding. Strategies that do not use the context may ignore
 * it.
 *
 * <p>{@code tenancyId} identifies the tenant owning the case — enables tenant-scoped CBR routing
 * and trust weighting. Required by all routing strategies that use tenant-specific history or
 * stored experiences.
 *
 * @param caseId the case instance UUID
 * @param capabilityName the capability being routed — used for trust score lookups and filtering
 * @param caseContext the current case context as a JSON node; may be {@code NullNode} when the case
 *     has no context
 * @param tenancyId the tenant ID owning the case; used for tenant-scoped CBR and trust lookups
 */
public record AgentRoutingContext(
    UUID caseId, String capabilityName, JsonNode caseContext, String tenancyId) {}
