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
package io.casehub.actorstate;

import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.platform.api.actor.ActorStateContributor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Contributes active Quartz job case IDs from the engine.
 *
 * <p>actorId equals workerId — same string, different naming convention per layer. Best-effort
 * snapshot: a job completing between this call and the HTTP response means engineActiveCaseIds may
 * transiently contain a case whose work just finished.
 */
@ApplicationScoped
public class EngineActorStateContributor implements ActorStateContributor {

  @Inject WorkerExecutionManager executionManager;

  @Override
  public String sourceName() {
    return "engine";
  }

  @Override
  public void contribute(final String actorId, final ActorStateAccumulator acc) {
    // Collect fully before contributing — satisfies atomic contribution contract.
    final var ids = executionManager.getActiveCaseIds(actorId);
    ids.forEach(acc::engineActiveCaseId);
  }
}
