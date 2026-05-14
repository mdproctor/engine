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
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * Reactive no-op WorkerProvisioner. Fails {@code provision} with {@link ProvisioningException} to
 * signal misconfiguration. All other operations complete with no side-effects. Active by default —
 * replace with a real reactive implementation when the reactive pipeline is needed.
 */
@DefaultBean
@ApplicationScoped
public class NoOpReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

  @Override
  public Uni<Worker> provision(Set<String> capabilities, ProvisionContext context) {
    return Uni.createFrom()
        .failure(
            new ProvisioningException(
                "No ReactiveWorkerProvisioner configured — add an @ApplicationScoped"
                    + " ReactiveWorkerProvisioner implementation"));
  }

  @Override
  public Uni<Void> terminate(String workerId) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Set<String>> getCapabilities() {
    return Uni.createFrom().item(Set.of());
  }
}
