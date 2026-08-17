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
import io.casehub.platform.api.mcp.CallbackEligible;
import io.casehub.worker.api.Worker;
import java.util.Set;

/**
 * Provisions and terminates workers for CaseEngine.
 *
 * <p>Called when a PlanItem is eligible but no workers with the required capabilities are
 * available. Implementations spin up actual compute: a tmux session (Claudony), a Docker container,
 * a Nono sandbox, etc.
 *
 * <p>Implementations are CDI beans ({@code @ApplicationScoped}). The default no-op throws {@link
 * ProvisioningException} to signal misconfiguration.
 */
@CallbackEligible(name = "worker-provisioner")
public interface WorkerProvisioner {

  /**
   * Provision a new worker with the given capabilities.
   *
   * @param capabilities required capability set for the PlanItem
   * @param context case context, pre-built worker context, and propagation
   * @return the provisioning outcome, including optional causal ledger entry linkage
   * @throws ProvisioningException if the worker cannot be started
   */
  ProvisionResult provision(Set<String> capabilities, ProvisionContext context);

  /**
   * Terminate a previously provisioned worker. No-op if the worker is unknown.
   *
   * @param workerId the worker name as returned by {@link Worker#getName()}
   * @param tenancyId the tenant that owns the case — avoids ambiguous lookups when the same
   *     workerId is used across tenants
   */
  void terminate(String workerId, String tenancyId);

  /**
   * Returns the capability tags this provisioner can supply. Used by CaseEngine to decide whether
   * to call this provisioner.
   */
  Set<String> getCapabilities();
}
