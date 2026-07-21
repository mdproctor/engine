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
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.engine.common.spi.query.CaseDefinitionQuery;
import io.casehub.engine.rest.dto.PagedResponse;
import io.casehub.engine.rest.dto.ProblemDetail;
import io.casehub.engine.rest.exception.EntityNotFoundException;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/case-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Case Definitions", description = "Query registered case definitions")
public class CaseDefinitionResource {

  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @RunOnVirtualThread
  @Operation(
      summary = "List all case definitions",
      description = "Returns a paginated list of all registered case definitions")
  @Parameter(name = "page", description = "Page number (1-indexed)", example = "1")
  @Parameter(name = "size", description = "Page size (1-100)", example = "20")
  @APIResponse(
      responseCode = "200",
      description = "Paginated list of case definitions",
      content = @Content(schema = @Schema(implementation = PagedResponse.class)))
  public PagedResponse<CaseDefinition> listAll(
      @QueryParam("page") @DefaultValue("1") @Min(1) int page,
      @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size) {
    var query = CaseDefinitionQuery.builder().page(page - 1).size(size).build();
    String tenancyId = currentPrincipal.tenancyId();
    var metaModels = metaModelRepository.query(query, tenancyId);
    var definitions = metaModels.stream().map(definitionRegistry::getCaseDefinition).toList();
    long total = metaModelRepository.count(query, tenancyId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new PagedResponse<>(definitions, page, size, total, totalPages);
  }

  @GET
  @Path("/{namespace}/{name}")
  @RunOnVirtualThread
  @Operation(
      summary = "Get definitions by namespace and name",
      description = "Returns all versions of a case definition matching the namespace and name")
  @Parameter(name = "namespace", description = "Case namespace", required = true, example = "acme")
  @Parameter(
      name = "name",
      description = "Case name",
      required = true,
      example = "Order Processing")
  @APIResponse(responseCode = "200", description = "List of matching case definitions")
  @APIResponse(
      responseCode = "404",
      description = "No definitions found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public java.util.List<CaseDefinition> getByNamespaceAndName(
      @PathParam("namespace") String namespace, @PathParam("name") String name) {
    var query = CaseDefinitionQuery.builder().namespace(namespace).name(name).build();
    String tenancyId = currentPrincipal.tenancyId();
    var metaModels = metaModelRepository.query(query, tenancyId);
    if (metaModels.isEmpty()) {
      throw new EntityNotFoundException(
          String.format(
              "No case definition found for namespace '%s' and name '%s'", namespace, name));
    }
    return metaModels.stream().map(definitionRegistry::getCaseDefinition).toList();
  }

  @GET
  @Path("/{namespace}/{name}/{version}")
  @RunOnVirtualThread
  @Operation(
      summary = "Get definition by key",
      description = "Returns a specific case definition by namespace, name, and version")
  @Parameter(name = "namespace", description = "Case namespace", required = true, example = "acme")
  @Parameter(
      name = "name",
      description = "Case name",
      required = true,
      example = "Order Processing")
  @Parameter(name = "version", description = "Case version", required = true, example = "1.0.0")
  @APIResponse(responseCode = "200", description = "Case definition found")
  @APIResponse(
      responseCode = "404",
      description = "Definition not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public CaseDefinition getByKey(
      @PathParam("namespace") String namespace,
      @PathParam("name") String name,
      @PathParam("version") String version) {
    var metaModel =
        definitionRegistry
            .findByIdentity(namespace, name, version)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        String.format(
                            "No case definition found for namespace '%s', name '%s', version '%s'",
                            namespace, name, version)));
    return definitionRegistry.getCaseDefinition(metaModel);
  }
}
