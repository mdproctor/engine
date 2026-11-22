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
package io.casehub.engine.common.internal.routing;

import io.casehub.api.model.AgentTaskTarget;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.ai.DeferredAgentConfig;
import io.casehub.worker.api.Worker;
import java.util.List;
import java.util.Set;

public final class SyntheticAgentTaskWorker {

  public static final String RESERVED_PREFIX = "__agent-task__";

  private SyntheticAgentTaskWorker() {}

  public static Worker create(String bindingName, AgentTaskTarget target) {
    var config =
        new DeferredAgentConfig.CapabilityConfig(
            target.capability(),
            target.domain(),
            target.model(),
            target.inputMapping(),
            target.outputMapping());

    return Worker.builder()
        .name(RESERVED_PREFIX + bindingName)
        .capabilityName(target.capability())
        .description("Synthetic agent-task worker for capability: " + target.capability())
        .function(AgentWorkerFunction.ofCapability(config))
        .build();
  }

  public static void validateNoCollision(List<Worker> workers, Set<String> syntheticNames) {
    for (Worker w : workers) {
      if (w.name().startsWith(RESERVED_PREFIX) && !syntheticNames.contains(w.name())) {
        throw new IllegalArgumentException(
            "Worker name '" + w.name() + "' uses reserved prefix '" + RESERVED_PREFIX + "'");
      }
    }
  }
}
