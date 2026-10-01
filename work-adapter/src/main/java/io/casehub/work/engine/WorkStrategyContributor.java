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
package io.casehub.work.engine;

import io.casehub.engine.runtime.routing.EngineStrategyResolver;
import io.casehub.work.api.spi.ClaimSlaPolicy;
import io.casehub.work.api.spi.InstanceAssignmentStrategy;
import io.casehub.work.api.spi.SlaBreachPolicy;
import io.casehub.work.api.spi.WorkerSelectionStrategy;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Registers casehub-work strategy beans with {@link EngineStrategyResolver}.
 *
 * <p>Quarkus ARC's {@code Instance<NamedStrategy>} does not resolve beans whose NamedStrategy
 * relationship is transitive (e.g. ContinuationPolicy → ClaimSlaPolicy → NamedStrategy).
 * Per-SPI-type {@code Instance<T>} injection finds them. This bean bridges the gap by registering
 * work strategies after the resolver is constructed.
 */
@ApplicationScoped
public class WorkStrategyContributor {

  @Inject EngineStrategyResolver resolver;
  @Inject @Any Instance<WorkerSelectionStrategy> workerStrategies;
  @Inject @Any Instance<ClaimSlaPolicy> claimPolicies;
  @Inject @Any Instance<SlaBreachPolicy> breachPolicies;
  @Inject @Any Instance<InstanceAssignmentStrategy> assignmentStrategies;

  void onStart(@Observes StartupEvent ev) {
    workerStrategies.forEach(s -> safeRegister(s));
    claimPolicies.forEach(s -> safeRegister(s));
    breachPolicies.forEach(s -> safeRegister(s));
    assignmentStrategies.forEach(s -> safeRegister(s));
  }

  private void safeRegister(io.casehub.platform.api.routing.NamedStrategy strategy) {
    try {
      resolver.registerEntry(strategy, false);
    } catch (IllegalStateException e) {
      // already registered — ignore
    }
  }
}
