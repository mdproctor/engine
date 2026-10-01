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
package io.casehub.engine.support.spring;

import io.casehub.engine.actorstate.ActorStateResource;
import io.casehub.engine.actorstate.ActorStateResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/actors", produces = MediaType.APPLICATION_JSON_VALUE)
public class ActorStateController {

  private final ActorStateResource resource;

  public ActorStateController(ActorStateResource resource) {
    this.resource = resource;
  }

  @GetMapping("/{actorId}/state")
  public ResponseEntity<ActorStateResponse> getActorState(@PathVariable("actorId") String actorId) {
    return ResponseEntity.ok(resource.getActorState(actorId));
  }
}
