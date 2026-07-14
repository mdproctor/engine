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

import static io.casehub.engine.common.internal.event.EventBusAddresses.BULK_SIGNAL_RECEIVED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.CASE_STARTED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.CASE_STATUS_CHANGED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.SIGNAL_RECEIVED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.TYPED_SIGNAL_RECEIVED;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.SettlementTimeoutException;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.BulkSignalReceivedEvent;
import io.casehub.engine.common.internal.event.CaseStartedEvent;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.SignalReceivedEvent;
import io.casehub.engine.common.internal.event.TypedSignalReceivedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.context.EpisodicLayerUpdater;
import io.casehub.engine.internal.context.WritableLayerImpl;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.ReactiveCaseMemoryStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
class CaseHubReactor {

  private static final Logger LOG = Logger.getLogger(CaseHubReactor.class);

  @ConfigProperty(name = "casehub.resilience.timeout.max-duration")
  Optional<Duration> maxDuration;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject ReactiveCaseInstanceRepository reactiveCaseInstanceRepository;

  @Inject EventBus eventBus;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  @Inject CurrentPrincipal currentPrincipal;

  @Inject ReactiveCaseMemoryStore reactiveCaseMemoryStore;

  @Inject JQEvaluator jqEvaluator;

  @Inject SignalSettlementTracker settlementTracker;

  CompletionStage<UUID> startCase(
      CaseDefinition definition, MutableCaseContext context, UUID caseId) {
    return startCaseInternal(definition, context, caseId, null, null, null);
  }

  CompletionStage<UUID> startCase(
      CaseDefinition definition,
      MutableCaseContext context,
      UUID caseId,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    return startCaseInternal(definition, context, caseId, parentCaseId, propagationContext, null);
  }

  CompletionStage<UUID> startCase(
      CaseDefinition definition,
      MutableCaseContext context,
      UUID caseId,
      Map<String, Object> semanticData) {
    return startCaseInternal(definition, context, caseId, null, null, semanticData);
  }

  CompletionStage<UUID> startCase(
      CaseDefinition definition,
      MutableCaseContext context,
      UUID caseId,
      Map<String, Object> semanticData,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    return startCaseInternal(
        definition, context, caseId, parentCaseId, propagationContext, semanticData);
  }

  private CompletionStage<UUID> startCaseInternal(
      CaseDefinition definition,
      MutableCaseContext context,
      UUID caseId,
      UUID parentCaseId,
      PropagationContext parentPropCtx,
      Map<String, Object> semanticData) {
    return buildInstance(definition, context, caseId, parentCaseId, parentPropCtx, semanticData)
        .chain(
            instance -> {
              LOG.info("Case started with caseId: " + instance.getUuid());
              // Use request() instead of publish() so the CompletionStage resolves only after
              // CaseStartedEventHandler has finished — context snapshot taken, CASE_STARTED
              // persisted, CONTEXT_CHANGED published. publish() is fire-and-forget and resolves
              // before the handler runs, creating a race window for callers that mutate the
              // context immediately after startCase() returns.
              return eventBus
                  .<Void>request(CASE_STARTED, new CaseStartedEvent(instance))
                  .replaceWith(instance);
            })
        .onItem()
        .transform(CaseInstance::getUuid)
        .subscribeAsCompletionStage();
  }

  private Uni<CaseInstance> buildInstance(
      CaseDefinition definition,
      MutableCaseContext context,
      UUID caseId,
      UUID parentCaseId,
      PropagationContext parentPropCtx,
      Map<String, Object> semanticData) {
    CaseMetaModel model = caseDefinitionRegistry.getCaseMetaModel(definition);

    final PropagationContext propagationContext;
    if (parentPropCtx != null) {
      propagationContext = parentPropCtx.createChild();
    } else {
      String traceId =
          traceIdProvider
              .currentTraceId()
              .filter(id -> !id.isBlank())
              .orElseGet(() -> UUID.randomUUID().toString());

      Map<String, String> identityAttrs =
          Map.of(
              "userId", currentPrincipal.actorId(),
              "roles", String.join(",", currentPrincipal.roles()));

      propagationContext =
          maxDuration
              .map(budget -> PropagationContext.createRoot(traceId, identityAttrs, budget))
              .orElse(PropagationContext.createRoot(traceId, identityAttrs));
    }

    // Populate semantic layer: definition defaults first, call-site overrides second.
    // Semantic must be frozen before the inter-case memory query (entityId JQ needs it).
    Map<String, Object> defSemanticData = definition.getSemanticData();
    if (defSemanticData != null && !defSemanticData.isEmpty()) {
      context.writableLayer(ContextLayer.SEMANTIC).setAll(defSemanticData);
    }
    if (semanticData != null && !semanticData.isEmpty()) {
      context.writableLayer(ContextLayer.SEMANTIC).setAll(semanticData);
    }
    context.freezeLayer(ContextLayer.SEMANTIC);
    EpisodicLayerUpdater.initBaseline(context);

    // Inter-case memory query — async, runs before episodic layer is frozen
    EpisodicMemoryConfig memCfg = definition.getEpisodicMemoryConfig();
    final Uni<Void> memoryQueryStep;

    if (memCfg != null) {
      memoryQueryStep =
          queryEpisodicMemory(context, memCfg)
              .invoke(
                  memories -> {
                    if (!memories.isEmpty()) {
                      List<Map<String, Object>> projected =
                          memories.stream()
                              .map(
                                  m -> {
                                    Map<String, Object> p = new LinkedHashMap<>();
                                    p.put("text", m.text());
                                    if (m.attributes() != null && !m.attributes().isEmpty()) {
                                      p.put("attributes", new LinkedHashMap<>(m.attributes()));
                                    }
                                    return p;
                                  })
                              .toList();
                      ((WritableLayerImpl) context.writableLayer(ContextLayer.EPISODIC))
                          .engineSet("memory", projected);
                    }
                  })
              .replaceWithVoid();
    } else {
      memoryQueryStep = Uni.createFrom().voidItem();
    }

    return memoryQueryStep.chain(
        () -> {
          // Freeze episodic layer after memory injection — episodic is engine-managed
          context.freezeLayer(ContextLayer.EPISODIC);

          // Pre-create user-defined layers declared in the case definition (eager init so
          // asJsonNode() and snapshot() include them even before any worker writes to them)
          List<String> declaredLayers = definition.getLayerNames();
          if (declaredLayers != null && !declaredLayers.isEmpty()) {
            for (String layerName : declaredLayers) {
              context.writableLayer(layerName);
            }
          }

          CaseInstance instance = new CaseInstance();
          instance.setUuid(caseId);
          instance.setCaseMetaModel(model);
          instance.setVersion(0L);
          instance.setState(CaseStatus.STARTING);
          instance.setCaseContext(context);
          instance.setPropagationContext(propagationContext);
          instance.setParentCaseId(parentCaseId);

          caseInstanceCache.put(instance);
          return reactiveCaseInstanceRepository.save(instance, currentPrincipal.tenancyId());
        });
  }

  private Uni<List<Memory>> queryEpisodicMemory(MutableCaseContext ctx, EpisodicMemoryConfig cfg) {
    try {
      // Evaluate entityId JQ against frozen semantic layer
      var semNode = ctx.layer(ContextLayer.SEMANTIC).asJsonNode();
      ValidationResult vr = jqEvaluator.eval(cfg.entityId(), semNode);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
        LOG.warnf("episodic.memory.entityId JQ evaluation failed: %s", vr.error());
        return Uni.createFrom().item(List.of());
      }

      var result = vr.output().get(0);
      List<String> entityIds;
      if (result.isTextual()) {
        entityIds = List.of(result.asText());
      } else if (result.isArray()) {
        entityIds = new ArrayList<>();
        result.forEach(n -> entityIds.add(n.asText()));
      } else {
        LOG.warnf("episodic.memory.entityId JQ result is neither string nor array: %s", result);
        return Uni.createFrom().item(List.of());
      }

      if (entityIds.isEmpty()) {
        return Uni.createFrom().item(List.of());
      }

      var domain = new MemoryDomain(cfg.domain());
      var tenantId = currentPrincipal.tenancyId();

      // No withCaseId() — inter-case query is cross-case by design
      MemoryQuery query =
          entityIds.size() == 1
              ? MemoryQuery.forEntity(entityIds.get(0), domain, tenantId).withLimit(cfg.recent())
              : MemoryQuery.forEntities(entityIds, domain, tenantId).withLimit(cfg.recent());

      return reactiveCaseMemoryStore
          .query(query)
          .onFailure()
          .recoverWithItem(
              t -> {
                LOG.warnf(
                    t, "EpisodicMemoryStore query failed — continuing without inter-case memory");
                return List.of();
              });

    } catch (Exception e) {
      LOG.warnf(e, "Failed to build episodic MemoryQuery");
      return Uni.createFrom().item(List.of());
    }
  }

  Uni<Void> signal(UUID caseId, String path, Object value) {
    return signal(caseId, path, value, null, null);
  }

  Uni<Void> signal(
      UUID caseId,
      String path,
      Object value,
      String triggerChannelId,
      String triggerCorrelationId) {
    String tenancyId = requireInstance(caseId).tenancyId;
    return eventBus
        .<Void>request(
            SIGNAL_RECEIVED,
            new SignalReceivedEvent(
                caseId, tenancyId, path, value, triggerChannelId, triggerCorrelationId))
        .replaceWithVoid();
  }

  Uni<Void> signalBulk(UUID caseId, Map<String, Object> updates) {
    String tenancyId = requireInstance(caseId).tenancyId;
    return eventBus
        .<Void>request(
            BULK_SIGNAL_RECEIVED, new BulkSignalReceivedEvent(caseId, tenancyId, updates))
        .replaceWithVoid();
  }

  Uni<Void> signalTyped(
      UUID caseId,
      String signalName,
      Object payload,
      Class<?> payloadType,
      String payloadTypeName) {
    String tenancyId = requireInstance(caseId).tenancyId;
    return eventBus
        .<Void>request(
            TYPED_SIGNAL_RECEIVED,
            new TypedSignalReceivedEvent(
                caseId, signalName, payload, payloadType, payloadTypeName, tenancyId))
        .replaceWithVoid();
  }

  Uni<CaseContext> signalAndAwait(UUID caseId, Map<String, Object> updates, Duration timeout) {
    String tenancyId = requireInstance(caseId).tenancyId;
    UUID signalId = settlementTracker.registerSignal(caseId);
    return eventBus
        .<Void>request(
            BULK_SIGNAL_RECEIVED,
            new BulkSignalReceivedEvent(caseId, tenancyId, updates, null, null, signalId))
        .replaceWithVoid()
        .chain(
            () -> {
              CompletableFuture<Void> future = settlementTracker.getFuture(signalId);
              if (future == null) {
                return Uni.createFrom().item(requireInstance(caseId).getCaseContext());
              }
              return Uni.createFrom()
                  .completionStage(
                      future.orTimeout(
                          timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                  .onFailure(java.util.concurrent.TimeoutException.class)
                  .transform(
                      t -> {
                        settlementTracker.remove(signalId);
                        return new SettlementTimeoutException(caseId, timeout);
                      })
                  .map(v -> requireInstance(caseId).getCaseContext());
            });
  }

  void cancelCase(UUID caseId) {
    final CaseInstance instance = requireInstance(caseId);
    final CaseStatus current = instance.getState();
    if (current == CaseStatus.COMPLETED
        || current == CaseStatus.FAULTED
        || current == CaseStatus.CANCELLED) {
      throw new IllegalStateException(
          "Cannot cancel case in terminal state " + current + ": caseId=" + caseId);
    }
    eventBus.publish(
        CASE_STATUS_CHANGED,
        new CaseStatusChanged(instance, current.name(), CaseStatus.CANCELLED.name()));
  }

  void suspendCase(UUID caseId) {
    final CaseInstance instance = requireInstance(caseId);
    if (instance.getState() != CaseStatus.RUNNING) {
      throw new IllegalStateException(
          "Can only suspend a RUNNING case, current state: "
              + instance.getState()
              + ": caseId="
              + caseId);
    }
    eventBus.publish(
        CASE_STATUS_CHANGED,
        new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.SUSPENDED.name()));
  }

  void resumeCase(UUID caseId) {
    final CaseInstance instance = requireInstance(caseId);
    if (instance.getState() != CaseStatus.SUSPENDED) {
      throw new IllegalStateException(
          "Can only resume a SUSPENDED case, current state: "
              + instance.getState()
              + ": caseId="
              + caseId);
    }
    eventBus.publish(
        CASE_STATUS_CHANGED,
        new CaseStatusChanged(instance, CaseStatus.SUSPENDED.name(), CaseStatus.RUNNING.name()));
  }

  private CaseInstance requireInstance(UUID caseId) {
    final CaseInstance instance = caseInstanceCache.get(caseId);
    if (instance == null) {
      throw new IllegalArgumentException("Case instance not found: " + caseId);
    }
    return instance;
  }

  CompletionStage<Object> query(UUID caseId, String path) {
    return CompletableFuture.supplyAsync(
        () -> {
          if (caseInstanceCache.get(caseId) == null) {
            throw new RuntimeException("Case instance not found for caseId: " + caseId);
          }
          return caseInstanceCache.get(caseId).getCaseContext().getPath(path);
        });
  }

  @SuppressWarnings("unchecked")
  <T> CompletionStage<T> query(UUID caseId, String path, Class<T> clazz) {
    return query(caseId, path)
        .thenApply(
            result -> {
              if (result == null) {
                return null;
              }
              if (clazz.isInstance(result)) {
                return clazz.cast(result);
              }
              throw new ClassCastException(
                  "Cannot cast " + result.getClass().getName() + " to " + clazz.getName());
            });
  }

  public Uni<List<EventLog>> eventLog(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes) {
    return reactiveEventLogRepository.findByCaseWithFilters(
        caseId, eventTypes, streamTypes, currentPrincipal.tenancyId());
  }
}
