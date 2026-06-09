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

import static io.casehub.engine.common.internal.event.EventBusAddresses.CASE_STARTED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.CASE_STATUS_CHANGED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.SIGNAL_RECEIVED;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextPanel;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseStartedEvent;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.SignalReceivedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Collection;
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

  @Inject CaseInstanceRepository caseInstanceRepository;

  @Inject EventBus eventBus;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @Inject EventLogRepository eventLogRepository;

  @Inject CurrentPrincipal currentPrincipal;

  CompletionStage<UUID> startCase(CaseDefinition definition, CaseContext context) {
    return startCaseInternal(definition, context, null, null, null);
  }

  CompletionStage<UUID> startCase(
      CaseDefinition definition,
      CaseContext context,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    return startCaseInternal(definition, context, parentCaseId, propagationContext, null);
  }

  CompletionStage<UUID> startCase(
      CaseDefinition definition, CaseContext context, Map<String, Object> semanticData) {
    return startCaseInternal(definition, context, null, null, semanticData);
  }

  CompletionStage<UUID> startCase(
      CaseDefinition definition,
      CaseContext context,
      Map<String, Object> semanticData,
      UUID parentCaseId,
      PropagationContext propagationContext) {
    return startCaseInternal(definition, context, parentCaseId, propagationContext, semanticData);
  }

  private CompletionStage<UUID> startCaseInternal(
      CaseDefinition definition,
      CaseContext context,
      UUID parentCaseId,
      PropagationContext parentPropCtx,
      Map<String, Object> semanticData) {
    return buildInstance(definition, context, parentCaseId, parentPropCtx, semanticData)
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
      CaseContext context,
      UUID parentCaseId,
      PropagationContext parentPropCtx,
      Map<String, Object> semanticData) {
    CaseMetaModel model = caseDefinitionRegistry.getCaseMetaModel(definition);

    PropagationContext propagationContext;
    if (parentPropCtx != null) {
      propagationContext = parentPropCtx.createChild();
    } else {
      String traceId =
          traceIdProvider
              .currentTraceId()
              .filter(id -> !id.isBlank())
              .orElseGet(() -> UUID.randomUUID().toString());

      propagationContext =
          maxDuration
              .map(
                  budget ->
                      PropagationContext.createRoot(traceId, Map.<String, String>of(), budget))
              .orElse(PropagationContext.createRoot(traceId));
    }

    // Populate semantic panel: definition defaults first, call-site overrides second
    if (context instanceof CaseContextImpl ctx) {
      Map<String, Object> defSemanticData = definition.getSemanticData();
      if (defSemanticData != null && !defSemanticData.isEmpty()) {
        ctx.writablePanel(ContextPanel.SEMANTIC).setAll(defSemanticData);
      }
      if (semanticData != null && !semanticData.isEmpty()) {
        ctx.writablePanel(ContextPanel.SEMANTIC).setAll(semanticData);
      }
      ctx.freezePanel(ContextPanel.SEMANTIC);
      ctx.freezePanel(ContextPanel.EPISODIC); // episodic is engine-managed
    }

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(model);
    instance.setVersion(0L);
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseContext(context);
    instance.setPropagationContext(propagationContext);
    instance.setParentCaseId(parentCaseId);

    caseInstanceCache.put(instance);
    return caseInstanceRepository.save(instance, currentPrincipal.tenancyId());
  }

  void signal(UUID caseId, String path, Object value) {
    eventBus.publish(SIGNAL_RECEIVED, new SignalReceivedEvent(caseId, path, value));
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
    return eventLogRepository.findByCaseWithFilters(
        caseId, eventTypes, streamTypes, currentPrincipal.tenancyId());
  }
}
