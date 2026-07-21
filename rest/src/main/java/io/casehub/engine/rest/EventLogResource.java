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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.query.EventLogQuery;
import io.casehub.engine.rest.dto.EventLogEntryResponse;
import io.casehub.engine.rest.dto.PagedResponse;
import io.casehub.engine.rest.dto.ProblemDetail;
import io.casehub.engine.rest.service.CaseService;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/cases/{caseId}/events")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Event Log", description = "Case event log and audit trail")
public class EventLogResource {

  @Inject EventLogRepository eventLogRepository;
  @Inject CaseService caseService;
  @Inject CurrentPrincipal currentPrincipal;

  @GET
  @RunOnVirtualThread
  @Operation(
      summary = "Get case event log",
      description = "Returns a paginated and filtered event log for a case instance")
  @Parameter(name = "caseId", description = "Case instance UUID", required = true)
  @Parameter(name = "page", description = "Page number (1-indexed)", example = "1")
  @Parameter(name = "size", description = "Page size (1-1000)", example = "50")
  @Parameter(
      name = "eventType",
      description = "Filter by event type (repeatable)",
      example = "CASE_STARTED")
  @Parameter(
      name = "streamType",
      description = "Filter by stream type (repeatable)",
      example = "CASE")
  @APIResponse(
      responseCode = "200",
      description = "Paginated event log",
      content = @Content(schema = @Schema(implementation = PagedResponse.class)))
  @APIResponse(
      responseCode = "404",
      description = "Case not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  public PagedResponse<EventLogEntryResponse> getEventLog(
      @PathParam("caseId") UUID caseId,
      @QueryParam("page") @DefaultValue("1") @Min(1) int page,
      @QueryParam("size") @DefaultValue("50") @Min(1) @Max(1000) int size,
      @QueryParam("eventType") List<String> eventTypeNames,
      @QueryParam("streamType") List<String> streamTypeNames) {

    caseService.requireCase(caseId, currentPrincipal.tenancyId());

    Set<CaseHubEventType> eventTypes =
        eventTypeNames == null || eventTypeNames.isEmpty()
            ? null
            : eventTypeNames.stream().map(CaseHubEventType::valueOf).collect(Collectors.toSet());

    Set<EventStreamType> streamTypes =
        streamTypeNames == null || streamTypeNames.isEmpty()
            ? null
            : streamTypeNames.stream().map(EventStreamType::valueOf).collect(Collectors.toSet());

    var query =
        EventLogQuery.builder(caseId)
            .eventTypes(eventTypes)
            .streamTypes(streamTypes)
            .page(page - 1)
            .size(size)
            .build();

    String tenancyId = currentPrincipal.tenancyId();
    var items =
        eventLogRepository.query(query, tenancyId).stream()
            .map(EventLogEntryResponse::from)
            .toList();
    long total = eventLogRepository.count(query, tenancyId);
    int totalPages = (int) Math.ceil((double) total / size);
    return new PagedResponse<>(items, page, size, total, totalPages);
  }
}
