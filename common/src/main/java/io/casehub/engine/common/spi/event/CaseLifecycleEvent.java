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
package io.casehub.engine.common.spi.event;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.ContextLayer;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import java.util.UUID;

/**
 * CDI event fired on every auditable case lifecycle transition.
 *
 * <p>Fired via {@code Event.fireAsync()} from Vert.x event-bus handlers so that optional modules
 * (e.g. casehub-ledger) can observe transitions without the engine depending on them. If no
 * observer is present the event fires into the void.
 *
 * @param caseId the case instance UUID
 * @param tenancyId the tenant that owns this case
 * @param commandType the actor intent — e.g. {@code "StartCase"}, {@code "SuspendCase"}
 * @param eventType the observable fact — e.g. {@code "CaseStarted"}, {@code "CaseSuspended"}
 * @param caseStatus snapshot of CaseStatus at transition time; null for non-status events
 * @param actorId the initiating actor; null for system-triggered events
 * @param actorRole the actor's role in this transition; null when not applicable
 * @param traceId OTel trace ID captured synchronously before fireAsync() — see GE-20260526-43a51d
 * @param caseDefinitionName the case definition name; null when meta model not yet associated
 * @param namespace the case definition namespace; null when meta model not yet associated
 * @param contextSnapshot working layer as JsonNode at fire time (point-in-time, read-only)
 */
public record CaseLifecycleEvent(
    UUID caseId,
    String tenancyId,
    String commandType,
    String eventType,
    String caseStatus,
    String actorId,
    String actorRole,
    String traceId,
    String caseDefinitionName,
    String namespace,
    JsonNode contextSnapshot) {

  public static CaseLifecycleEvent of(
      CaseInstance caseInstance,
      String commandType,
      String eventType,
      String actorId,
      String actorRole,
      String traceId) {
    CaseMetaModel mm = caseInstance.getCaseMetaModel();
    String defName = mm != null ? mm.getName() : null;
    String ns = mm != null ? mm.getNamespace() : null;
    JsonNode snapshot = null;
    if (caseInstance.getCaseContext() != null) {
      snapshot = caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();
    }
    return new CaseLifecycleEvent(
        caseInstance.getUuid(),
        caseInstance.tenancyId,
        commandType,
        eventType,
        caseInstance.getState() != null ? caseInstance.getState().name() : null,
        actorId,
        actorRole,
        traceId,
        defName,
        ns,
        snapshot);
  }

  public static CaseLifecycleEvent of(
      UUID caseId,
      String tenancyId,
      String commandType,
      String eventType,
      String caseStatus,
      String actorId,
      String actorRole,
      String traceId) {
    return new CaseLifecycleEvent(
        caseId,
        tenancyId,
        commandType,
        eventType,
        caseStatus,
        actorId,
        actorRole,
        traceId,
        null,
        null,
        null);
  }
}
