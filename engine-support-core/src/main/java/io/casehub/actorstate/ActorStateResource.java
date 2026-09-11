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
package io.casehub.actorstate;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST endpoint for GET /actors/{actorId}/state.
 *
 * <p>Authorization: inherits application-level security. No platform-level role restriction.
 *
 * <p>tenancyId is not an explicit parameter — each store handles tenant scoping implicitly (Panache
 * via security context; qhorus is single-tenant; trust scores are by actorId only; Quartz is
 * in-memory without tenancy).
 */
@PermitAll
@Path("/actors")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ActorStateResource {

  @Inject ActorStateAggregator aggregator;

  @GET
  @Path("/{actorId}/state")
  public ActorStateResponse getActorState(@PathParam("actorId") final String actorId) {
    return aggregator.forActor(actorId);
  }
}
