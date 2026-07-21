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
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseStartedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.context.WritableLayerImpl;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.scheduler.SchedulerService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Records a {@code CASE_STARTED} event and notifies listeners that the context has changed. */
@ApplicationScoped
public class CaseStartedEventHandler {

  private static final Logger LOG = Logger.getLogger(CaseStartedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject EventBus eventBus;

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  @Inject SchedulerService schedulerService;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject CaseChannelProvider caseChannelProvider;

  @Inject ReactiveCaseInstanceRepository reactiveCaseInstanceRepository;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject CbrRetrievalService cbrRetrievalService;

  @ConsumeEvent(value = EventBusAddresses.CASE_STARTED, blocking = true)
  public Uni<Void> onCaseStarted(CaseStartedEvent event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    final CaseInstance instance = event.instance();

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.CASE_STARTED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setPayload(instance.getCaseContext().asJsonNode());
    eventLog.setMetadata(
        OBJECT_MAPPER.createObjectNode().put("status", instance.getState().name()));

    caseChannelProvider.openChannel(instance.getUuid(), "coordination");

    return reactiveEventLogRepository
        .append(eventLog, instance.tenancyId)
        .chain(() -> schedulerService.registerScheduledTriggers(instance))
        .invoke(
            () -> {
              instance.setState(CaseStatus.RUNNING);
            })
        .chain(() -> reactiveCaseInstanceRepository.update(instance, instance.tenancyId))
        .chain(
            () -> {
              EventLog runningLog = new EventLog();
              runningLog.setCaseId(instance.getUuid());
              runningLog.setEventType(CaseHubEventType.CASE_STATUS_CHANGED);
              runningLog.setStreamType(EventStreamType.CASE);
              runningLog.setTimestamp(Instant.now());
              runningLog.setMetadata(
                  OBJECT_MAPPER
                      .createObjectNode()
                      .put("oldStatus", CaseStatus.STARTING.name())
                      .put("newStatus", CaseStatus.RUNNING.name()));
              return reactiveEventLogRepository.append(runningLog, instance.tenancyId);
            })
        .chain(() -> injectCbrExperiences(instance))
        .invoke(
            () -> {
              lifecycleEvents
                  .fireAsync(
                      CaseLifecycleEvent.of(
                          instance, "StartCase", "CaseStarted", null, "System", traceId))
                  .whenComplete(
                      (v, t) -> {
                        if (t != null) {
                          LOG.warnf(
                              t,
                              "CaseLifecycleEvent observer failed for caseId=%s event=CaseStarted",
                              instance.getUuid());
                        }
                      });
            })
        .invoke(
            () ->
                eventBus.publish(
                    EventBusAddresses.CONTEXT_CHANGED,
                    new CaseContextChangedEvent(
                        instance, instance.getCaseContext().snapshot(), null)));
  }

  private Uni<Void> injectCbrExperiences(CaseInstance instance) {
    CaseMetaModel metaModel = instance.getCaseMetaModel();
    if (metaModel == null) {
      return Uni.createFrom().voidItem();
    }
    CaseDefinition definition = caseDefinitionRegistry.getCaseDefinition(metaModel);
    if (definition == null || definition.getCbrConfig() == null) {
      return Uni.createFrom().voidItem();
    }
    List<RetrievedExperience> experiences = cbrRetrievalService.retrieve(definition, instance);
    if (!experiences.isEmpty()) {
      List<Map<String, Object>> serialised =
          OBJECT_MAPPER.convertValue(
              experiences, new TypeReference<List<Map<String, Object>>>() {});
      MutableCaseContext mutableContext = (MutableCaseContext) instance.getCaseContext();
      WritableLayerImpl layer =
          (WritableLayerImpl) mutableContext.writableLayer(ContextLayer.WORKING);
      layer.engineSet("cbrExperiences", serialised);
      layer.engineSet(
          "cbrBestSimilarity",
          experiences.stream().mapToDouble(RetrievedExperience::similarityScore).max().orElse(0.0));
      layer.engineSet("cbrMatchCount", experiences.size());
      layer.engineSet("cbrOutcomeConsistency", computeOutcomeConsistency(experiences));
    }
    return Uni.createFrom().voidItem();
  }

  private static double computeOutcomeConsistency(List<RetrievedExperience> experiences) {
    Map<String, Long> freq =
        experiences.stream()
            .map(RetrievedExperience::outcome)
            .filter(java.util.Objects::nonNull)
            .collect(
                java.util.stream.Collectors.groupingBy(
                    java.util.function.Function.identity(),
                    java.util.stream.Collectors.counting()));
    if (freq.isEmpty()) {
      return 0.0;
    }
    return (double) java.util.Collections.max(freq.values()) / experiences.size();
  }
}
