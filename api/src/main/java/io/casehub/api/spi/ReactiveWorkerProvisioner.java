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

import io.casehub.api.model.ProvisionContext;
import io.smallrye.mutiny.Uni;
import java.util.Set;

/**
 * Reactive mirror of {@link WorkerProvisioner} — all methods return {@link Uni}.
 *
 * <p>Use this interface when integrating with non-blocking I/O stacks (Vert.x, reactive Panache,
 * etc.). Semantics are identical to the blocking SPI: {@code provision} fails the {@code Uni} with
 * {@link ProvisioningException} when a worker cannot be started.
 */
public interface ReactiveWorkerProvisioner {

  /**
   * Provision a new worker reactively.
   *
   * @param capabilities required capability set
   * @param context case context, pre-built worker context, and propagation
   * @return {@code Uni} emitting the provisioning outcome on success, or failing with {@link
   *     ProvisioningException}
   */
  Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context);

  /**
   * Terminate a previously provisioned worker. No-op if the worker is unknown.
   *
   * @param workerId the worker name as returned by {@link Worker#getName()}
   * @param tenancyId the tenant that owns the case — avoids ambiguous lookups when the same
   *     workerId is used across tenants
   * @return a {@code Uni} completing with {@code null} on success
   */
  Uni<Void> terminate(String workerId, String tenancyId);

  /**
   * Returns the capability tags this provisioner can supply.
   *
   * @return a {@code Uni} emitting the set of supported capabilities (may be empty)
   */
  Uni<Set<String>> getCapabilities();
}
