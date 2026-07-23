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
import io.casehub.engine.rest.dto.ProblemDetail;
import io.casehub.engine.rest.dto.SendSignalRequest;
import io.casehub.engine.rest.dto.SignalResponse;
import io.casehub.engine.rest.service.CaseService;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
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

@Path("/api/v1/cases/{caseId}/signals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Signals", description = "Send signals to running cases")
public class SignalResource {

  @Inject CaseHubRuntime runtime;
  @Inject CaseService caseService;
  @Inject CurrentPrincipal currentPrincipal;

  @POST
  @RunOnVirtualThread
  @Operation(summary = "Send signal to a case")
  @Parameter(name = "caseId", description = "Case instance UUID", required = true)
  @APIResponse(
      responseCode = "200",
      description = "Signal accepted",
      content = @Content(schema = @Schema(implementation = SignalResponse.class)))
  @APIResponse(
      responseCode = "400",
      description = "Invalid request",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public SignalResponse sendSignal(
      @PathParam("caseId") UUID caseId, @Valid SendSignalRequest request) {
    caseService.requireCase(caseId, currentPrincipal.tenancyId());
    runtime.signal(caseId, request.path(), request.value());
    return new SignalResponse(caseId, "accepted", "Signal delivered to case");
  }
}
