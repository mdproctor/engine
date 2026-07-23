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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.SignalRejectedException;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.context.CaseContextImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
class CaseHubRuntimeImpl implements CaseHubRuntime {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject CaseHubReactor reactor;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject io.casehub.platform.api.routing.StrategyResolver strategyResolver;

  @Inject @io.casehub.engine.common.qualifier.CrossTenant
  io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository crossTenantCaseInstanceRepository;

  @Override
  public UUID startCase(CaseDefinition definition) {
    var factory = resolveFactory(definition);
    UUID caseId = UUID.randomUUID();
    return reactor.startCase(definition, new CaseContextImpl(factory, caseId), caseId);
  }

  @Override
  public UUID startCase(CaseDefinition definition, Object inputData) {
    var factory = resolveFactory(definition);
    UUID caseId = UUID.randomUUID();
    return reactor.startCase(definition, createContext(factory, caseId, inputData), caseId);
  }

  @Override
  public UUID startCase(
      CaseDefinition definition,
      Object inputData,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    var factory = resolveFactory(definition);
    UUID caseId = UUID.randomUUID();
    return reactor.startCase(
        definition,
        createContext(factory, caseId, inputData),
        caseId,
        parentCaseId,
        propagationContext);
  }

  @Override
  public UUID startCase(
      CaseDefinition definition, Object inputData, Map<String, Object> semanticData) {
    var factory = resolveFactory(definition);
    UUID caseId = UUID.randomUUID();
    return reactor.startCase(
        definition, createContext(factory, caseId, inputData), caseId, semanticData);
  }

  @Override
  public UUID startCase(
      CaseDefinition definition,
      Object inputData,
      Map<String, Object> semanticData,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    var factory = resolveFactory(definition);
    UUID caseId = UUID.randomUUID();
    return reactor.startCase(
        definition,
        createContext(factory, caseId, inputData),
        caseId,
        semanticData,
        parentCaseId,
        propagationContext);
  }

  private io.casehub.api.context.CaseContextStoreFactory resolveFactory(CaseDefinition definition) {
    var factory =
        strategyResolver.resolve(
            io.casehub.api.context.CaseContextStoreFactory.class,
            definition.getContextStoreFactory());
    if (factory.isDurable()) {
      throw new UnsupportedOperationException(
          "CaseContextStoreFactory '"
              + factory.id()
              + "' reports isDurable()=true but "
              + "recovery path is not yet wired — durable factories will silently lose case "
              + "state on JVM restart. Implement recovery migration before deploying "
              + "durable factories.");
    }
    return factory;
  }

  private CaseContextImpl createContext(
      io.casehub.api.context.CaseContextStoreFactory factory, UUID caseId, Object inputData) {
    CaseContextImpl context = new CaseContextImpl(factory, caseId);
    Map<String, Object> inputMap = toContextMap(inputData);
    if (!inputMap.isEmpty()) {
      context.setAll(inputMap);
    }
    return context;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toContextMap(Object inputData) {
    if (inputData == null) return Map.of();
    if (inputData instanceof Map) return (Map<String, Object>) inputData;
    return OBJECT_MAPPER.convertValue(inputData, MAP_TYPE);
  }

  @Override
  public void signal(UUID caseId, String path, Object value) {
    reactor.signal(caseId, path, value, null, null);
  }

  @Override
  public void signal(UUID caseId, String path, Object value, Map<String, Object> signalMetadata) {
    reactor.signal(caseId, path, value, signalMetadata);
  }

  @Override
  public void signal(
      UUID caseId,
      String path,
      Object value,
      String triggerChannelId,
      String triggerCorrelationId) {
    reactor.signal(caseId, path, value, triggerChannelId, triggerCorrelationId);
  }

  @Override
  public void signal(UUID caseId, Map<String, Object> updates) {
    reactor.signalBulk(caseId, updates);
  }

  @Override
  public CaseContext signalAndAwait(UUID caseId, Map<String, Object> updates, Duration timeout) {
    return reactor.signalAndAwait(caseId, updates, timeout);
  }

  @Override
  public <T> void signal(UUID caseId, SignalType<T> signalType, T payload) {
    Objects.requireNonNull(payload, "Typed signal payload must not be null");
    CaseInstance instance = caseInstanceCache.get(caseId);
    if (instance == null) {
      instance = crossTenantCaseInstanceRepository.findByUuid(caseId);
      if (instance == null) {
        throw new IllegalArgumentException("CaseInstance not found: " + caseId);
      }
      caseInstanceCache.put(instance);
    }
    CaseStatus state = instance.getState();
    if (state == CaseStatus.COMPLETED
        || state == CaseStatus.FAULTED
        || state == CaseStatus.CANCELLED) {
      throw new SignalRejectedException("Case " + caseId + " is in terminal state: " + state);
    }
    CaseMetaModel meta = instance.getCaseMetaModel();
    if (meta != null) {
      CaseDefinition definition = caseDefinitionRegistry.getCaseDefinition(meta);
      if (definition != null && !definition.getSignals().isEmpty()) {
        var declared =
            definition.getSignals().stream()
                .filter(s -> s.name().equals(signalType.name()))
                .findFirst()
                .orElse(null);
        if (declared == null) {
          throw new SignalRejectedException(
              "Signal '" + signalType.name() + "' not declared on definition " + meta.getName());
        }
        if (!declared.payloadType().equals(signalType.payloadType())) {
          throw new SignalRejectedException(
              "Signal '"
                  + signalType.name()
                  + "' declared with type "
                  + declared.payloadType().getName()
                  + " but received "
                  + signalType.payloadType().getName());
        }
      }
    }
    reactor.signalTyped(
        caseId,
        signalType.name(),
        payload,
        signalType.payloadType(),
        signalType.payloadType().getName());
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
  public Object query(UUID caseId, String path) {
    return reactor.query(caseId, path);
  }

  @Override
  public <T> T query(UUID caseId, String path, Class<T> clazz) {
    return reactor.query(caseId, path, clazz);
  }

  @Override
  public List<CaseEventLogRecord> eventLog(UUID caseId) {
    return eventLog(caseId, Set.of());
  }

  @Override
  public List<CaseEventLogRecord> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes) {
    return eventLog(caseId, eventTypes, Set.of());
  }

  @Override
  public List<CaseEventLogRecord> eventLog(
      UUID caseId, Set<CaseHubEventType> eventTypes, Set<EventStreamType> streamTypes) {
    List<EventLog> list = reactor.eventLog(caseId, eventTypes, streamTypes);
    return list.stream()
        .map(
            event ->
                new CaseEventLogRecord(
                    event.getEventType(),
                    event.getStreamType(),
                    event.getTimestamp(),
                    event.getPayload(),
                    event.getMetadata()))
        .toList();
  }
}
