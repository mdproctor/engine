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
package io.casehub.api.spi;

import java.util.UUID;

/**
 * Outcome of a successful {@link WorkerProvisioner#provision} or {@link
 * WorkerProvisioner#provision} call.
 *
 * <p>{@code causedByEntryId} is the ledger entry ID of the COMMAND that triggered provisioning.
 * Provisioner implementations that can resolve this ID (e.g. by correlating against a Qhorus
 * message ledger) set it here; implementations that cannot leave it {@code null}. The engine passes
 * it through to the audit event so that ledger observers can establish causal linkage without
 * round-tripping through the engine's internal state.
 *
 * <p>Will be non-null only after engine#231 threads Qhorus trigger context (channelId +
 * correlationId) through {@link io.casehub.api.model.ProvisionContext}.
 *
 * <p>{@code resolvedWorkerId} is the identifier of the provisioned worker instance. This field
 * eliminates the workerName==agentId convention. Provisioner implementations that can resolve the
 * worker instance ID set it here; implementations that cannot leave it {@code null}. See
 * engine#760.
 */
public record ProvisionResult(
    UUID causedByEntryId, @org.jspecify.annotations.Nullable String resolvedWorkerId) {

  /** Convenience factory for provisioners that do not resolve a causal ledger entry. */
  public static ProvisionResult empty() {
    return new ProvisionResult(null, null);
  }
}
