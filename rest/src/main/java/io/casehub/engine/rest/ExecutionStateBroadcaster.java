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
package io.casehub.engine.rest;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseContextUpdatedEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.plan.execution.CasePlanModelSnapshotProvider;
import io.casehub.engine.plan.execution.ExecutionSnapshotStore;
import io.casehub.engine.rest.dto.ExecutionStateSnapshot;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.BackPressureFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ExecutionStateBroadcaster {

  private static final Logger LOG = Logger.getLogger(ExecutionStateBroadcaster.class);

  private record CaseSnapshotEvent(UUID caseId, ExecutionStateSnapshot snapshot) {}

  private final BroadcastProcessor<CaseSnapshotEvent> processor = BroadcastProcessor.create();

  @Inject CasePlanModelSnapshotProvider planModelProvider;
  @Inject ExecutionSnapshotStore snapshotStore;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CaseInstanceRepository caseInstanceRepository;

  void onPlanItemChanged(@ObservesAsync PlanItemStateChangedEvent event) {
    compose(event.caseId(), event.tenancyId());
  }

  void onContextUpdated(@ObservesAsync CaseContextUpdatedEvent event) {
    compose(event.caseId(), event.tenancyId());
  }

  public Multi<ExecutionStateSnapshot> stream(UUID caseId) {
    return processor
        .toHotStream()
        .filter(e -> caseId.equals(e.caseId()))
        .map(CaseSnapshotEvent::snapshot);
  }

  public ExecutionStateSnapshot composeInitial(UUID caseId, String tenancyId) {
    var planModel = planModelProvider.getSnapshot(caseId, tenancyId).orElse(null);
    var dagPlan = snapshotStore.getDagPlan(caseId, tenancyId).orElse(null);
    var dagResult = snapshotStore.getDagResult(caseId, tenancyId).orElse(null);
    if (planModel == null && dagPlan == null && dagResult == null) {
      return null;
    }
    CaseDefinition definition = resolveDefinition(caseId, tenancyId);
    return ExecutionStateSnapshot.compose(caseId, planModel, dagPlan, dagResult, definition);
  }

  private void compose(UUID caseId, String tenancyId) {
    try {
      var planModel = planModelProvider.getSnapshot(caseId, tenancyId).orElse(null);
      var dagPlan = snapshotStore.getDagPlan(caseId, tenancyId).orElse(null);
      var dagResult = snapshotStore.getDagResult(caseId, tenancyId).orElse(null);
      CaseDefinition definition = resolveDefinition(caseId, tenancyId);
      var snapshot =
          ExecutionStateSnapshot.compose(caseId, planModel, dagPlan, dagResult, definition);
      processor.onNext(new CaseSnapshotEvent(caseId, snapshot));
    } catch (BackPressureFailure ignored) {
    } catch (Exception e) {
      LOG.debugf("Failed to compose execution state for case %s: %s", caseId, e.getMessage());
    }
  }

  private CaseDefinition resolveDefinition(UUID caseId, String tenancyId) {
    try {
      CaseInstance instance = caseInstanceRepository.findByUuid(caseId, tenancyId);
      if (instance != null && instance.getCaseMetaModel() != null) {
        return definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
