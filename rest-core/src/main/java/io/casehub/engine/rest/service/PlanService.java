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
package io.casehub.engine.rest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.EntityNotFoundException;
import io.casehub.engine.common.spi.recovery.ExecutionSnapshotStore;
import io.casehub.engine.plan.execution.CasePlanModelSnapshotProvider;
import io.casehub.engine.plan.execution.ExecutionStateSnapshot;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DagResultView;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import io.casehub.engine.plan.snapshot.ExecutionStateView;
import io.casehub.engine.plan.snapshot.PlanItemDefinitionSnapshot;
import io.casehub.engine.plan.snapshot.PlanModelView;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.List;
import java.util.UUID;

public class PlanService {

  private final CaseService caseService;
  private final CasePlanModelSnapshotProvider planModelProvider;
  private final ExecutionSnapshotStore snapshotStore;
  private final CurrentPrincipal currentPrincipal;
  private final ObjectMapper objectMapper;

  public PlanService(
      CaseService caseService,
      CasePlanModelSnapshotProvider planModelProvider,
      ExecutionSnapshotStore snapshotStore,
      CurrentPrincipal currentPrincipal,
      ObjectMapper objectMapper) {
    this.caseService = caseService;
    this.planModelProvider = planModelProvider;
    this.snapshotStore = snapshotStore;
    this.currentPrincipal = currentPrincipal;
    this.objectMapper = objectMapper;
  }

  public PlanModelView getPlanModel(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    var snapshot =
        planModelProvider
            .getSnapshot(caseId, resolvedTenancyId)
            .orElseThrow(
                () -> new EntityNotFoundException("Plan model not found for case: " + caseId));
    return PlanViewMapper.toPlanModelView(snapshot);
  }

  public List<PlanItemDefinitionSnapshot> getPlanDefinitions(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    return planModelProvider.getDefinitions(caseId, resolvedTenancyId);
  }

  public DecompositionSnapshot getDecomposition(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    return snapshotStore
        .getDecomposition(caseId, resolvedTenancyId)
        .orElseThrow(
            () -> new EntityNotFoundException("Decomposition not found for case: " + caseId));
  }

  public DagPlanSnapshot getDagPlan(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    return snapshotStore
        .getDagPlan(caseId, resolvedTenancyId)
        .orElseThrow(() -> new EntityNotFoundException("DAG plan not found for case: " + caseId));
  }

  public DagResultView getDagResult(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    var dagResult =
        snapshotStore
            .getDagResult(caseId, resolvedTenancyId)
            .orElseThrow(
                () -> new EntityNotFoundException("DAG result not found for case: " + caseId));
    return PlanViewMapper.toDagResultView(dagResult);
  }

  public ExecutionStateView getExecutionState(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    var planModel = planModelProvider.getSnapshot(caseId, resolvedTenancyId).orElse(null);
    var dagPlan = snapshotStore.getDagPlan(caseId, resolvedTenancyId).orElse(null);
    var dagResult = snapshotStore.getDagResult(caseId, resolvedTenancyId).orElse(null);
    var snapshot = ExecutionStateSnapshot.compose(caseId, planModel, dagPlan, dagResult);
    return PlanViewMapper.toExecutionStateView(snapshot);
  }
}
