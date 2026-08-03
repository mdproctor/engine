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

import io.casehub.engine.rest.dto.CaseStreamEvent;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/api/v1/cases/{caseId}/stream")
@Tag(name = "Case Stream", description = "SSE stream for real-time case updates")
public class CaseStreamResource {

  @Inject CaseStreamBroadcaster broadcaster;

  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Stream case events via SSE",
      description =
          "Multiplexed SSE stream for plan-item state transitions and context updates. "
              + "Events are notifications — clients should re-fetch authoritative REST endpoints "
              + "for current state. Event ordering is not guaranteed.")
  public Multi<CaseStreamEvent> stream(@PathParam("caseId") UUID caseId) {
    return broadcaster.stream(caseId);
  }
}
