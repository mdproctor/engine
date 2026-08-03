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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.worker.api.WorkerFunction;

/**
 * Registry for {@link WorkerFunctionProvider} instances.
 *
 * <p>Dispatches YAML worker node construction to the appropriate {@link WorkerFunctionProvider} by
 * iterating all registered providers until one handles the node. All CDI beans implementing {@link
 * WorkerFunctionProvider} are discovered automatically.
 *
 * @see WorkerFunctionProvider
 */
public interface WorkerFunctionProviderRegistry {

  WorkerFunction<?, ?> createFunction(JsonNode rawWorkerNode);

  default java.util.List<DiscoveredWorker> discoverWorkers(JsonNode rawWorkerNode) {
    return java.util.List.of();
  }
}
