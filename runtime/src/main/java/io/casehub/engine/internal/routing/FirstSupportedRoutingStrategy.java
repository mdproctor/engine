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
package io.casehub.engine.internal.routing;

import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionRoutingStrategy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class FirstSupportedRoutingStrategy implements WorkerExecutionRoutingStrategy {

  @Override
  public Optional<WorkerExecutionManager> select(
      List<WorkerExecutionManager> candidates,
      Worker worker,
      Capability capability,
      String tenancyId) {
    for (WorkerExecutionManager candidate : candidates) {
      if (candidate.supports(capability.name(), tenancyId)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }
}
