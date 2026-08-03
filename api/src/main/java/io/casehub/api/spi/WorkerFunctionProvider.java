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
 * SPI for constructing {@link WorkerFunction} instances from raw YAML worker nodes.
 *
 * <p>Implementations detect whether a YAML worker node contains their recognized structure (e.g.,
 * {@code agent:}, {@code flow:}) and construct the corresponding {@link WorkerFunction}.
 *
 * <p>Modules register providers by implementing this interface and exposing them as CDI beans. The
 * YAML mapper delegates function construction to the {@link WorkerFunctionProviderRegistry}, which
 * iterates all providers until one handles the node.
 *
 * @see WorkerFunctionProviderRegistry
 */
public interface WorkerFunctionProvider {

  /**
   * Returns {@code true} if this provider can construct a {@link WorkerFunction} from the given
   * YAML worker node.
   *
   * @param rawWorkerNode the YAML worker node
   * @return {@code true} if this provider handles the node
   */
  boolean handles(JsonNode rawWorkerNode);

  WorkerFunction<?, ?> create(JsonNode rawWorkerNode);

  default java.util.List<DiscoveredWorker> discoverWorkers(JsonNode rawWorkerNode) {
    return java.util.List.of();
  }
}
