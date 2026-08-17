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
package io.casehub.engine.graphql.dto;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import java.util.UUID;
import org.eclipse.microprofile.graphql.Type;

@Type("CaseLifecycleEvent")
public record CaseLifecycleEventType(
    UUID caseId,
    String eventType,
    String commandType,
    String caseStatus,
    String actorId,
    String actorRole,
    String caseDefinitionName,
    String namespace,
    String satisfiedGoalName,
    String satisfiedGoalKind) {

  public static CaseLifecycleEventType from(CaseLifecycleEvent event) {
    return new CaseLifecycleEventType(
        event.caseId(),
        event.eventType(),
        event.commandType(),
        event.caseStatus(),
        event.actorId(),
        event.actorRole(),
        event.caseDefinitionName(),
        event.namespace(),
        event.satisfiedGoalName(),
        event.satisfiedGoalKind());
  }
}
