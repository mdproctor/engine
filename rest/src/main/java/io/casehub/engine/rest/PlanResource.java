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
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.plan.execution.CasePlanModelSnapshot;
import io.casehub.engine.plan.execution.CasePlanModelSnapshotProvider;
import io.casehub.engine.plan.execution.DagResultSnapshot;
import io.casehub.engine.plan.execution.ExecutionSnapshotStore;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import io.casehub.engine.plan.snapshot.PlanItemDefinitionSnapshot;
import io.casehub.engine.rest.dto.ExecutionStateSnapshot;
import io.casehub.engine.rest.dto.ProblemDetail;
import io.casehub.engine.rest.exception.EntityNotFoundException;
import io.casehub.engine.rest.service.CaseService;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/cases/{caseId}/plan")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Plan Snapshots",
    description = "HTN decomposition, DAG execution, and plan model snapshots")
public class PlanResource {

  @Inject CaseService caseService;
  @Inject CasePlanModelSnapshotProvider planModelProvider;
  @Inject ExecutionSnapshotStore snapshotStore;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject CaseDefinitionRegistry definitionRegistry;

  @GET
  @Path("/model")
  @RunOnVirtualThread
  @Operation(summary = "Get live case plan model snapshot")
  @APIResponse(responseCode = "200", description = "Plan model snapshot")
  @APIResponse(
      responseCode = "404",
      description = "No plan model for this case",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public CasePlanModelSnapshot getPlanModel(@PathParam("caseId") UUID caseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return planModelProvider
        .getSnapshot(caseId, currentPrincipal.tenancyId())
        .orElseThrow(() -> new EntityNotFoundException("No plan model for case: " + caseId));
  }

  @GET
  @Path("/definitions")
  @RunOnVirtualThread
  @Operation(summary = "Get plan item definition hierarchy")
  @APIResponse(responseCode = "200", description = "Plan item definitions")
  public List<PlanItemDefinitionSnapshot> getDefinitions(@PathParam("caseId") UUID caseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return planModelProvider.getDefinitions(caseId, currentPrincipal.tenancyId());
  }

  @GET
  @Path("/decomposition")
  @RunOnVirtualThread
  @Operation(summary = "Get HTN decomposition tree snapshot")
  @APIResponse(responseCode = "200", description = "Decomposition snapshot")
  @APIResponse(
      responseCode = "404",
      description = "No decomposition captured",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public DecompositionSnapshot getDecomposition(@PathParam("caseId") UUID caseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return snapshotStore
        .getDecomposition(caseId, currentPrincipal.tenancyId())
        .orElseThrow(
            () -> new EntityNotFoundException("No decomposition snapshot for case: " + caseId));
  }

  @GET
  @Path("/dag")
  @RunOnVirtualThread
  @Operation(summary = "Get DAG plan snapshot")
  @APIResponse(responseCode = "200", description = "DAG plan snapshot")
  @APIResponse(
      responseCode = "404",
      description = "No DAG plan captured",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public DagPlanSnapshot getDagPlan(@PathParam("caseId") UUID caseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return snapshotStore
        .getDagPlan(caseId, currentPrincipal.tenancyId())
        .orElseThrow(() -> new EntityNotFoundException("No DAG plan snapshot for case: " + caseId));
  }

  @GET
  @Path("/dag/result")
  @RunOnVirtualThread
  @Operation(summary = "Get DAG execution result snapshot")
  @APIResponse(responseCode = "200", description = "DAG result snapshot")
  @APIResponse(
      responseCode = "404",
      description = "No DAG result captured",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public DagResultSnapshot getDagResult(@PathParam("caseId") UUID caseId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    return snapshotStore
        .getDagResult(caseId, currentPrincipal.tenancyId())
        .orElseThrow(
            () -> new EntityNotFoundException("No DAG result snapshot for case: " + caseId));
  }

  @GET
  @Path("/state")
  @RunOnVirtualThread
  @Operation(summary = "Get composed execution state snapshot")
  @APIResponse(responseCode = "200", description = "Execution state snapshot")
  @APIResponse(
      responseCode = "404",
      description = "No execution state for this case",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public ExecutionStateSnapshot getExecutionState(@PathParam("caseId") UUID caseId) {
    var instance = caseService.requireCaseAccess(caseId, AclAction.READ);
    String tenancyId = currentPrincipal.tenancyId();
    var planModel = planModelProvider.getSnapshot(caseId, tenancyId);
    var dagPlan = snapshotStore.getDagPlan(caseId, tenancyId);
    var dagResult = snapshotStore.getDagResult(caseId, tenancyId);
    if (planModel.isEmpty() && dagPlan.isEmpty() && dagResult.isEmpty()) {
      throw new EntityNotFoundException("No execution state for case: " + caseId);
    }
    CaseDefinition definition = null;
    try {
      definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    } catch (Exception ignored) {
    }
    return ExecutionStateSnapshot.compose(
        caseId, planModel.orElse(null), dagPlan.orElse(null), dagResult.orElse(null), definition);
  }
}
