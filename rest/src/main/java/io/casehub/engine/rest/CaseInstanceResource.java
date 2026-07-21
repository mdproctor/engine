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
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import io.casehub.engine.rest.dto.CaseInstanceResponse;
import io.casehub.engine.rest.dto.PagedResponse;
import io.casehub.engine.rest.dto.ProblemDetail;
import io.casehub.engine.rest.dto.StartCaseRequest;
import io.casehub.engine.rest.service.CaseService;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/cases")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Case Instances", description = "Case instance lifecycle and context")
public class CaseInstanceResource {

  @Inject CaseService caseService;
  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseHubRuntime runtime;
  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @RunOnVirtualThread
  @Operation(summary = "List case instances")
  @Parameter(name = "page", description = "Page number (1-indexed)", example = "1")
  @Parameter(name = "size", description = "Page size (1-100)", example = "20")
  @Parameter(name = "status", description = "Filter by case status", example = "RUNNING")
  @Parameter(name = "namespace", description = "Filter by namespace", example = "acme")
  @Parameter(name = "name", description = "Filter by case name", example = "Order Processing")
  @APIResponse(responseCode = "200", description = "Paginated list of case instances")
  public PagedResponse<CaseInstanceResponse> listCases(
      @QueryParam("page") @DefaultValue("1") @Min(1) int page,
      @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size,
      @QueryParam("status") CaseStatus status,
      @QueryParam("namespace") String namespace,
      @QueryParam("name") String name) {
    var query =
        CaseInstanceQuery.builder()
            .status(status)
            .namespace(namespace)
            .name(name)
            .page(page - 1)
            .size(size)
            .build();
    String tenancyId = currentPrincipal.tenancyId();
    var items =
        instanceRepository.query(query, tenancyId).stream()
            .map(CaseInstanceResponse::from)
            .toList();
    long total = instanceRepository.count(query, tenancyId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new PagedResponse<>(items, page, size, total, totalPages);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RunOnVirtualThread
  @Operation(summary = "Start a new case instance")
  @APIResponse(
      responseCode = "201",
      description = "Case instance started",
      content = @Content(schema = @Schema(implementation = CaseInstanceResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "Case definition not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public Response startCase(@Valid StartCaseRequest request) {
    var ref = request.definition();
    Map<String, Object> context = request.context();

    var instance =
        caseService.startCase(
            ref.namespace(), ref.name(), ref.version(), context, currentPrincipal.tenancyId());

    return Response.status(Response.Status.CREATED)
        .entity(CaseInstanceResponse.from(instance))
        .build();
  }

  @GET
  @Path("/{caseId}")
  @RunOnVirtualThread
  @Operation(summary = "Get case instance by ID")
  @APIResponse(
      responseCode = "200",
      description = "Case instance found",
      content = @Content(schema = @Schema(implementation = CaseInstanceResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public CaseInstanceResponse getCaseInstance(@PathParam("caseId") UUID caseId) {
    var instance = caseService.requireCase(caseId, currentPrincipal.tenancyId());
    return CaseInstanceResponse.from(instance);
  }

  @GET
  @Path("/{caseId}/context")
  @RunOnVirtualThread
  @Operation(summary = "Get full case context")
  @APIResponse(responseCode = "200", description = "Case context data")
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public Response getContext(@PathParam("caseId") UUID caseId) {
    caseService.requireCase(caseId, currentPrincipal.tenancyId());
    Object context = runtime.query(caseId, ".").toCompletableFuture().join();
    return Response.ok(context).build();
  }

  @GET
  @Path("/{caseId}/context/{path}")
  @RunOnVirtualThread
  @Operation(summary = "Get case context by path")
  @Parameter(
      name = "path",
      description = "Dot-notation context path",
      required = true,
      example = "customer.name")
  @APIResponse(responseCode = "200", description = "Value at context path")
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public Response getContextPath(@PathParam("caseId") UUID caseId, @PathParam("path") String path) {
    caseService.requireCase(caseId, currentPrincipal.tenancyId());
    Object value = runtime.query(caseId, path).toCompletableFuture().join();
    return Response.ok(value).build();
  }
}
