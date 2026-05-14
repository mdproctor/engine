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
package io.casehub.engine.internal.worker;

import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.Worker;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.WorkerProvisioner;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * Default no-op WorkerProvisioner that throws on every provision() call. Signals misconfiguration —
 * replace with a real implementation (e.g. Claudony's ClaudonyWorkerProvisioner) before
 * provisioning is needed.
 */
@DefaultBean
@ApplicationScoped
public class NoOpWorkerProvisioner implements WorkerProvisioner {

  @Override
  public Worker provision(Set<String> capabilities, ProvisionContext context) {
    throw new ProvisioningException(
        "No WorkerProvisioner configured — add an @ApplicationScoped @Priority(1) WorkerProvisioner implementation");
  }

  @Override
  public void terminate(String workerId) {
    // intentional no-op — nothing to terminate
  }

  @Override
  public Set<String> getCapabilities() {
    return Set.of();
  }
}
