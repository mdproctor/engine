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
package io.casehub.engine.common.spi.scheduler;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.List;
import java.util.Optional;

/**
 * SPI: selects which {@link WorkerExecutionManager} should handle a worker execution when multiple
 * backends are available.
 *
 * <p>Called by {@code CompositeWorkerExecutionManager} after filtering backends via {@link
 * WorkerExecutionManager#supports(String, String)}. The strategy receives only eligible candidates
 * and must return one of them, or {@code Optional.empty()} if none are suitable.
 *
 * <p>The default implementation ({@code FirstSupportedRoutingStrategy}) returns the first
 * candidate. Consumer implementations can route based on worker metadata, capability properties, or
 * tenant configuration.
 */
public interface WorkerExecutionRoutingStrategy {

  /**
   * Selects a {@link WorkerExecutionManager} from the list of eligible candidates.
   *
   * @param candidates all discovered backends sorted by priority (highest first)
   * @param worker the worker to be executed
   * @param capability the capability being invoked
   * @param tenancyId the tenant ID for multi-tenant deployments
   * @return the selected manager, or {@code Optional.empty()} if none are suitable
   */
  Optional<WorkerExecutionManager> select(
      List<WorkerExecutionManager> candidates,
      Worker worker,
      Capability capability,
      String tenancyId);
}
