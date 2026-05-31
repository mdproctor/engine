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
 * CDI event fired after a worker successfully completes execution.
 *
 * <p>Fired via {@code Event.fireAsync()} alongside {@link CaseLifecycleEvent} so that optional
 * modules (e.g. casehub-engine-ledger) can write a tamper-evident {@code WorkerDecisionEntry}
 * without the engine depending on the ledger. If no observer is present the event fires into the
 * void.
 *
 * <p>The {@code capabilityTag} is the capability name from the binding that triggered this worker
 * (e.g. {@code "sar-drafting"}). Null when no matching binding is found — should not occur in
 * practice but the field is nullable to avoid blocking the worker completion path.
 *
 * @param caseId the case instance UUID
 * @param tenancyId the tenant that owns this case
 * @param workerId the worker name from the case definition (e.g. {@code "sar-drafting-agent-v1"})
 * @param capabilityTag the capability name exercised; null if not determinable
 * @param traceId OTel trace ID captured synchronously before fireAsync()
 */
public record WorkerDecisionEvent(
    UUID caseId, String tenancyId, String workerId, String capabilityTag, String traceId) {}
