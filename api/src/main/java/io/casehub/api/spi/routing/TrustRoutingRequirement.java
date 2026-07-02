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

import java.util.List;

/**
 * Compliance evidence wrapper for trust routing decisions.
 *
 * @param requirementId regulatory requirement identifier (e.g. "FATF-R20-TRUST-ROUTING")
 * @param citation human-readable regulatory citation
 * @param mechanism description of how the requirement is met
 * @param status current compliance status
 * @param decisions routing decision records supporting this requirement
 */
public record TrustRoutingRequirement(
    String requirementId,
    String citation,
    String mechanism,
    RequirementStatus status,
    List<io.casehub.api.spi.routing.RoutingDecisionRecord> decisions) {}
