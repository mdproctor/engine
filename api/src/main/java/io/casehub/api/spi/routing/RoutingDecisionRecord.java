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

import java.util.UUID;

/**
 * Compliance audit record for a trust-weighted routing decision.
 *
 * @param capabilityTag the capability being routed
 * @param workerId the selected worker
 * @param trustScoreAtRouting trust score at decision time; null if bootstrap
 * @param thresholdApplied the threshold that was applied
 * @param evidenceEntryId UUID reference to the attestation or ledger entry
 */
public record RoutingDecisionRecord(
    String capabilityTag,
    String workerId,
    Double trustScoreAtRouting,
    double thresholdApplied,
    UUID evidenceEntryId) {}
