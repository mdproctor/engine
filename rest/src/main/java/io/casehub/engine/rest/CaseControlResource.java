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

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.engine.rest.dto.CaseControlRequest;
import io.casehub.engine.rest.dto.CaseControlResponse;
import io.casehub.engine.rest.dto.ProblemDetail;
import io.casehub.engine.rest.service.CaseService;
import io.casehub.platform.api.acl.AclAction;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/cases/{caseId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Case Control", description = "Case lifecycle operations (suspend, resume, cancel)")
public class CaseControlResource {

  @Inject CaseHubRuntime runtime;
  @Inject CaseService caseService;

  @POST
  @Path("suspend")
  @RunOnVirtualThread
  @Operation(summary = "Suspend a running case")
  @Parameter(name = "caseId", description = "Case instance UUID", required = true)
  @APIResponse(
      responseCode = "200",
      description = "Case suspended",
      content = @Content(schema = @Schema(implementation = CaseControlResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @APIResponse(
      responseCode = "409",
      description = "Invalid state transition",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public CaseControlResponse suspend(@PathParam("caseId") UUID caseId, CaseControlRequest request) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.suspendCase(caseId);
    return new CaseControlResponse(caseId, "suspend", "completed");
  }

  @POST
  @Path("resume")
  @RunOnVirtualThread
  @Operation(summary = "Resume a suspended case")
  @Parameter(name = "caseId", description = "Case instance UUID", required = true)
  @APIResponse(
      responseCode = "200",
      description = "Case resumed",
      content = @Content(schema = @Schema(implementation = CaseControlResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @APIResponse(
      responseCode = "409",
      description = "Invalid state transition",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public CaseControlResponse resume(@PathParam("caseId") UUID caseId, CaseControlRequest request) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.resumeCase(caseId);
    return new CaseControlResponse(caseId, "resume", "completed");
  }

  @POST
  @Path("cancel")
  @RunOnVirtualThread
  @Operation(summary = "Cancel a running case")
  @Parameter(name = "caseId", description = "Case instance UUID", required = true)
  @APIResponse(
      responseCode = "200",
      description = "Case cancelled",
      content = @Content(schema = @Schema(implementation = CaseControlResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @APIResponse(
      responseCode = "409",
      description = "Invalid state transition",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public CaseControlResponse cancel(@PathParam("caseId") UUID caseId, CaseControlRequest request) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.cancelCase(caseId);
    return new CaseControlResponse(caseId, "cancel", "completed");
  }
}
