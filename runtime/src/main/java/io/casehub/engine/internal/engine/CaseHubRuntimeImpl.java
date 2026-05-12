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
package io.casehub.engine.internal.engine;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.internal.context.CaseContextImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
class CaseHubRuntimeImpl implements CaseHubRuntime {

  @Inject CaseHubReactor reactor;

  @Override
  public CompletionStage<UUID> startCase(CaseDefinition definition) {
    return reactor.startCase(definition, new CaseContextImpl());
  }

  @Override
  public CompletionStage<UUID> startCase(CaseDefinition definition, Map<String, Object> inputData) {
    return reactor.startCase(definition, new CaseContextImpl(inputData));
  }

  @Override
  public CompletionStage<UUID> startCase(
      CaseDefinition definition,
      Map<String, Object> inputData,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    return reactor.startCase(
        definition, new CaseContextImpl(inputData), parentCaseId, propagationContext);
  }

  @Override
  public void signal(UUID caseId, String path, Object value) {
    reactor.signal(caseId, path, value);
  }

  @Override
  public void cancelCase(UUID caseId) {
    reactor.cancelCase(caseId);
  }

  @Override
  public void suspendCase(UUID caseId) {
    reactor.suspendCase(caseId);
  }

  @Override
  public void resumeCase(UUID caseId) {
    reactor.resumeCase(caseId);
  }

  @Override
  public CompletionStage<Object> query(UUID caseId, String path) {
    return reactor.query(caseId, path);
  }

  @Override
  public <T> CompletionStage<T> query(UUID caseId, String path, Class<T> clazz) {
    return reactor.query(caseId, path, clazz);
  }

  @Override
  public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId) {
    return eventLog(caseId, Set.of());
  }

  @Override
  public CompletionStage<List<CaseEventLogRecord>> eventLog(
      UUID caseId, Set<CaseHubEventType> eventTypes) {
    return eventLog(caseId, eventTypes, Set.of());
  }

  @Override
  public CompletionStage<List<CaseEventLogRecord>> eventLog(
      UUID caseId, Set<CaseHubEventType> eventTypes, Set<EventStreamType> streamTypes) {
    return reactor
        .eventLog(caseId, eventTypes, streamTypes)
        .onItem()
        .transform(
            list ->
                list.stream()
                    .map(
                        event ->
                            new CaseEventLogRecord(
                                event.getEventType(),
                                event.getStreamType(),
                                event.getTimestamp(),
                                event.getPayload(),
                                event.getMetadata()))
                    .toList())
        .subscribe()
        .asCompletionStage();
  }
}
