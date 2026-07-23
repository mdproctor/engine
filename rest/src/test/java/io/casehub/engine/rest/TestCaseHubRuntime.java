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

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class TestCaseHubRuntime implements CaseHubRuntime {

  final Map<UUID, Map<String, Object>> caseContexts = new ConcurrentHashMap<>();
  final Map<UUID, UUID> startedCases = new ConcurrentHashMap<>();

  @Override
  public UUID startCase(CaseDefinition definition) {
    return startCase(definition, Map.of());
  }

  @Override
  public UUID startCase(CaseDefinition definition, Object inputData) {
    UUID caseId = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    Map<String, Object> ctx = inputData instanceof Map ? (Map<String, Object>) inputData : Map.of();
    caseContexts.put(caseId, ctx);
    startedCases.put(caseId, caseId);
    return caseId;
  }

  @Override
  public UUID startCase(
      CaseDefinition definition,
      Object inputData,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    return startCase(definition, inputData);
  }

  @Override
  public UUID startCase(
      CaseDefinition definition, Object inputData, Map<String, Object> semanticData) {
    return startCase(definition, inputData);
  }

  @Override
  public UUID startCase(
      CaseDefinition definition,
      Object inputData,
      Map<String, Object> semanticData,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    return startCase(definition, inputData);
  }

  @Override
  public void signal(UUID caseId, String path, Object value) {
    requireCase(caseId);
  }

  @Override
  public void cancelCase(UUID caseId) {
    requireCase(caseId);
  }

  @Override
  public void suspendCase(UUID caseId) {
    requireCase(caseId);
  }

  @Override
  public void resumeCase(UUID caseId) {
    requireCase(caseId);
  }

  @Override
  public Object query(UUID caseId, String path) {
    requireCase(caseId);
    Map<String, Object> ctx = caseContexts.getOrDefault(caseId, Map.of());
    return new MapCaseContext(ctx);
  }

  @Override
  public <T> T query(UUID caseId, String path, Class<T> clazz) {
    requireCase(caseId);
    Map<String, Object> ctx = caseContexts.getOrDefault(caseId, Map.of());
    return clazz.cast(ctx);
  }

  @Override
  public List<CaseEventLogRecord> eventLog(UUID caseId) {
    return List.of();
  }

  @Override
  public List<CaseEventLogRecord> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes) {
    return List.of();
  }

  @Override
  public List<CaseEventLogRecord> eventLog(
      UUID caseId, Set<CaseHubEventType> eventTypes, Set<EventStreamType> streamTypes) {
    return List.of();
  }

  private void requireCase(UUID caseId) {
    if (!startedCases.containsKey(caseId) && !caseContexts.containsKey(caseId)) {
      throw new IllegalArgumentException("Case instance not found for caseId: " + caseId);
    }
  }

  public void setContext(UUID caseId, Map<String, Object> context) {
    caseContexts.put(caseId, context);
  }
}
