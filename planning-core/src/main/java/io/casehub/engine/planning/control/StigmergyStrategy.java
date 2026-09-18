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
package io.casehub.engine.planning.control;

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.engine.internal.stigmergy.StigmergyCoordinator;
import io.casehub.engine.planning.plan.CasePlanModel;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

public class StigmergyStrategy implements PlanningStrategy {

  private static final Logger LOG = Logger.getLogger(StigmergyStrategy.class);

  private final StigmergyCoordinator coordinator;

  public StigmergyStrategy(StigmergyCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Override
  public String id() {
    return "stigmergy";
  }

  @Override
  public String getName() {
    return "Stigmergy Strategy";
  }

  @Override
  public List<Binding> select(
      CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible) {
    UUID caseId = context.caseId();

    if (!coordinator.isStigmergyCase(caseId)) {
      var agentIds = eligible.stream().map(Binding::getName).toList();
      var stigmergyConfig = context.definition().getStigmergyConfig();
      coordinator.initializeCase(caseId, agentIds, stigmergyConfig);
      LOG.infof("Stigmergy case initialized: caseId=%s, agents=%d", caseId, agentIds.size());
      return List.copyOf(eligible);
    }

    return List.of();
  }
}
