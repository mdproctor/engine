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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.event.CaseStartedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.scheduler.SchedulerService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseStartedEventHandlerTest {

  private CaseStartedEventHandler handler;
  private CbrRetrievalService cbrRetrievalService;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private EventLogRepository eventLogRepo;
  private CaseInstanceRepository caseInstanceRepo;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    handler = new CaseStartedEventHandler();
    EventBus eventBus = mock(EventBus.class);
    eventLogRepo = mock(EventLogRepository.class);
    SchedulerService schedulerService = mock(SchedulerService.class);
    Event<CaseLifecycleEvent> lifecycleEvents = mock(Event.class);
    CaseChannelProvider channelProvider = mock(CaseChannelProvider.class);
    caseInstanceRepo = mock(CaseInstanceRepository.class);
    LedgerTraceIdProvider traceIdProvider = mock(LedgerTraceIdProvider.class);
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    cbrRetrievalService = mock(CbrRetrievalService.class);

    inject(handler, "eventBus", eventBus);
    inject(handler, "eventLogRepository", eventLogRepo);
    inject(handler, "schedulerService", schedulerService);
    inject(handler, "lifecycleEvents", lifecycleEvents);
    inject(handler, "caseChannelProvider", channelProvider);
    inject(handler, "caseInstanceRepository", caseInstanceRepo);
    inject(handler, "traceIdProvider", traceIdProvider);
    inject(handler, "caseDefinitionRegistry", caseDefinitionRegistry);
    inject(handler, "cbrRetrievalService", cbrRetrievalService);

    // eventLogRepo.append and schedulerService.registerScheduledTriggers are void — no stub needed
    when(caseInstanceRepo.update(any(CaseInstance.class), any()))
        .thenAnswer(inv -> inv.getArgument(0, CaseInstance.class));
    when(traceIdProvider.currentTraceId()).thenReturn(Optional.empty());
    when(lifecycleEvents.fireAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  void cbrExperiences_injected_into_context_when_config_present() {
    CaseDefinition definition =
        CaseDefinition.builder().namespace("test").name("test-case").version("1.0").build();
    CbrConfig config =
        CbrConfig.builder()
            .feature("severity", ".severity")
            .domain("test-domain")
            .topK(5)
            .minSimilarity(0.5)
            .build();
    definition.setCbrConfig(config);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("test-case");
    metaModel.setVersion("1.0");

    CaseInstance instance = createCaseInstance(metaModel);

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    RetrievedExperience experience =
        new RetrievedExperience(
            "similar problem",
            "applied solution",
            "COMPLETED",
            0.95,
            0.85,
            Map.of("severity", "HIGH"),
            List.of(),
            Map.of());
    when(cbrRetrievalService.retrieve(eq(definition), eq(instance)))
        .thenReturn(List.of(experience));

    handler.onCaseStarted(new CaseStartedEvent(instance));

    Object cbrExperiences =
        instance.getCaseContext().layer(ContextLayer.WORKING).get("cbrExperiences");
    assertThat(cbrExperiences).isNotNull();
    assertThat(cbrExperiences).isInstanceOf(List.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> experiences = (List<Map<String, Object>>) cbrExperiences;
    assertThat(experiences).hasSize(1);
    assertThat(experiences.get(0)).containsEntry("problem", "similar problem");
    assertThat(experiences.get(0)).containsEntry("outcome", "COMPLETED");
    assertThat(experiences.get(0)).containsEntry("similarityScore", 0.85);
  }

  @Test
  void no_cbrConfig_skips_injection() {
    CaseDefinition definition =
        CaseDefinition.builder().namespace("test").name("no-cbr-case").version("1.0").build();

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("no-cbr-case");
    metaModel.setVersion("1.0");

    CaseInstance instance = createCaseInstance(metaModel);

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    handler.onCaseStarted(new CaseStartedEvent(instance));

    Object cbrExperiences =
        instance.getCaseContext().layer(ContextLayer.WORKING).get("cbrExperiences");
    assertThat(cbrExperiences).isNull();
  }

  @Test
  void empty_experiences_not_written_to_context() {
    CaseDefinition definition =
        CaseDefinition.builder().namespace("test").name("empty-cbr-case").version("1.0").build();
    CbrConfig config =
        CbrConfig.builder()
            .feature("severity", ".severity")
            .domain("test-domain")
            .topK(5)
            .minSimilarity(0.5)
            .build();
    definition.setCbrConfig(config);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("empty-cbr-case");
    metaModel.setVersion("1.0");

    CaseInstance instance = createCaseInstance(metaModel);

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
    when(cbrRetrievalService.retrieve(definition, instance)).thenReturn(List.of());

    handler.onCaseStarted(new CaseStartedEvent(instance));

    Object cbrExperiences =
        instance.getCaseContext().layer(ContextLayer.WORKING).get("cbrExperiences");
    assertThat(cbrExperiences).isNull();
  }

  @Test
  void cbrSummaryStats_written_when_experiences_present() {
    CaseDefinition definition =
        CaseDefinition.builder().namespace("test").name("stats-case").version("1.0").build();
    CbrConfig config =
        CbrConfig.builder()
            .feature("severity", ".severity")
            .domain("test-domain")
            .topK(5)
            .minSimilarity(0.5)
            .build();
    definition.setCbrConfig(config);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("stats-case");
    metaModel.setVersion("1.0");
    CaseInstance instance = createCaseInstance(metaModel);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    RetrievedExperience exp1 =
        new RetrievedExperience(
            "problem1", "solution1", "COMPLETED", 0.9, 0.85, Map.of(), List.of(), Map.of());
    RetrievedExperience exp2 =
        new RetrievedExperience(
            "problem2", "solution2", "COMPLETED", 0.8, 0.72, Map.of(), List.of(), Map.of());
    RetrievedExperience exp3 =
        new RetrievedExperience(
            "problem3", "solution3", "FAULTED", 0.7, 0.65, Map.of(), List.of(), Map.of());
    when(cbrRetrievalService.retrieve(eq(definition), eq(instance)))
        .thenReturn(List.of(exp1, exp2, exp3));

    handler.onCaseStarted(new CaseStartedEvent(instance));

    var ctx = instance.getCaseContext().layer(ContextLayer.WORKING);
    assertThat(ctx.get("cbrBestSimilarity")).isEqualTo(0.85);
    assertThat(ctx.get("cbrMatchCount")).isEqualTo(3);
    assertThat((double) ctx.get("cbrOutcomeConsistency"))
        .isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  void cbrSummaryStats_not_written_when_experiences_empty() {
    CaseDefinition definition =
        CaseDefinition.builder().namespace("test").name("empty-stats-case").version("1.0").build();
    CbrConfig config =
        CbrConfig.builder()
            .feature("severity", ".severity")
            .domain("test-domain")
            .topK(5)
            .minSimilarity(0.5)
            .build();
    definition.setCbrConfig(config);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("empty-stats-case");
    metaModel.setVersion("1.0");
    CaseInstance instance = createCaseInstance(metaModel);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
    when(cbrRetrievalService.retrieve(definition, instance)).thenReturn(List.of());

    handler.onCaseStarted(new CaseStartedEvent(instance));

    var ctx = instance.getCaseContext().layer(ContextLayer.WORKING);
    assertThat(ctx.get("cbrBestSimilarity")).isNull();
    assertThat(ctx.get("cbrMatchCount")).isNull();
    assertThat(ctx.get("cbrOutcomeConsistency")).isNull();
  }

  @Test
  void cbrOutcomeConsistency_zero_when_all_outcomes_null() {
    CaseDefinition definition =
        CaseDefinition.builder().namespace("test").name("null-outcome-case").version("1.0").build();
    CbrConfig config =
        CbrConfig.builder()
            .feature("severity", ".severity")
            .domain("test-domain")
            .topK(5)
            .minSimilarity(0.5)
            .build();
    definition.setCbrConfig(config);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("null-outcome-case");
    metaModel.setVersion("1.0");
    CaseInstance instance = createCaseInstance(metaModel);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    RetrievedExperience exp1 =
        new RetrievedExperience(
            "problem1", "solution1", null, null, 0.90, Map.of(), List.of(), Map.of());
    RetrievedExperience exp2 =
        new RetrievedExperience(
            "problem2", "solution2", null, null, 0.80, Map.of(), List.of(), Map.of());
    when(cbrRetrievalService.retrieve(eq(definition), eq(instance)))
        .thenReturn(List.of(exp1, exp2));

    handler.onCaseStarted(new CaseStartedEvent(instance));

    var ctx = instance.getCaseContext().layer(ContextLayer.WORKING);
    assertThat(ctx.get("cbrBestSimilarity")).isEqualTo(0.90);
    assertThat(ctx.get("cbrMatchCount")).isEqualTo(2);
    assertThat(ctx.get("cbrOutcomeConsistency")).isEqualTo(0.0);
  }

  private CaseInstance createCaseInstance(CaseMetaModel metaModel) {
    CaseInstance ci = new CaseInstance();
    ci.setUuid(UUID.randomUUID());
    ci.tenancyId = "test-tenant";
    ci.setCaseContext(new CaseContextImpl());
    ci.setCaseMetaModel(metaModel);
    ci.setState(CaseStatus.STARTING);
    return ci;
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
