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
 * Routing context passed to {@link ImplementationRoutingStrategy#select}.
 *
 * @param caseId the case instance UUID
 * @param capabilityName the capability being routed
 * @param caseContext the current case context as a JSON node (working layer)
 */
public record ImplementationRoutingContext(
    UUID caseId, String capabilityName, JsonNode caseContext) {}
