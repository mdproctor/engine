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
import java.util.List;
import java.util.UUID;

/**
 * Routing context passed to {@link HumanTaskRoutingStrategy#select}. Carries everything the
 * strategy needs for decision-making, excluding the candidates it chooses from.
 *
 * @param caseId the case instance UUID
 * @param bindingName the binding name — matching key for plan trace analysis (equivalent to
 *     capabilityName for agent routing)
 * @param tenancyId the tenant owning the case
 * @param caseContext the current case context as a JSON node (working layer)
 * @param experiences retrieved similar cases from CBR (empty list if CBR is not configured)
 */
public record HumanTaskRoutingContext(
    UUID caseId,
    String bindingName,
    String tenancyId,
    JsonNode caseContext,
    List<RetrievedExperience> experiences) {}
