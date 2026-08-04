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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.DiscoveredWorker;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.worker.api.WorkerFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Default implementation of {@link WorkerFunctionProviderRegistry}.
 *
 * <p>Dispatches YAML worker node construction to the appropriate {@link WorkerFunctionProvider} by
 * iterating all registered providers until one handles the node. All CDI beans implementing {@link
 * WorkerFunctionProvider} are discovered automatically. Add a new provider bean to support
 * additional worker types without modifying this class or the runtime.
 */
@ApplicationScoped
public class DefaultWorkerFunctionProviderRegistry implements WorkerFunctionProviderRegistry {

  private final Iterable<WorkerFunctionProvider> providers;

  @Inject
  public DefaultWorkerFunctionProviderRegistry(Instance<WorkerFunctionProvider> providers) {
    this.providers = providers;
  }

  DefaultWorkerFunctionProviderRegistry(Iterable<WorkerFunctionProvider> providers) {
    this.providers = providers;
  }

  @Override
  public WorkerFunction<?, ?> createFunction(JsonNode rawWorkerNode) {
    for (WorkerFunctionProvider provider : providers) {
      if (provider.handles(rawWorkerNode)) {
        return provider.create(rawWorkerNode);
      }
    }
    return null;
  }

  @Override
  public List<DiscoveredWorker> discoverWorkers(final JsonNode rawWorkerNode) {
    for (final WorkerFunctionProvider provider : providers) {
      if (provider.handles(rawWorkerNode)) {
        final List<DiscoveredWorker> discovered = provider.discoverWorkers(rawWorkerNode);
        if (!discovered.isEmpty()) {
          return discovered;
        }
      }
    }
    return List.of();
  }
}
