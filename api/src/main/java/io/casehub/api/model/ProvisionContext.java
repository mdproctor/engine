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
package io.casehub.api.model;

import io.casehub.api.context.PropagationContext;
import java.util.UUID;

/**
 * Input to {@code WorkerProvisioner.provision()}.
 *
 * <p>Contains all information needed to spin up a new worker for a case: the case identifier, the
 * declared task type (maps to a capability string), the fully-built {@link WorkerContext} that will
 * be injected into the worker's startup prompt, and the propagation context for distributed
 * tracing. {@code workerContext} is nullable — callers that have not yet built one may pass {@code
 * null}.
 *
 * <p>{@code tenancyId} identifies the tenant that owns the case being provisioned. Provisioner
 * implementations use this to resolve tenant-specific endpoints via {@code EndpointRegistry}.
 *
 * <p>{@code triggerChannelId} and {@code triggerCorrelationId} carry the Qhorus channel ID and
 * {@code correlationId} of the COMMAND message that triggered this provisioning, when known. Both
 * are nullable: engine-internal call sites currently pass {@code null} because the engine does not
 * yet receive Qhorus trigger context at the point of provisioning (see engine#231 for the follow-on
 * work to thread this through the CaseFile-update API). Provisioner implementations that received a
 * Qhorus COMMAND may use these fields to establish causal linkage in the ledger (see claudony#94).
 */
public record ProvisionContext(
    UUID caseId,
    String tenancyId,
    String taskType,
    WorkerContext workerContext,
    PropagationContext propagationContext,
    String triggerChannelId,
    String triggerCorrelationId) {}
