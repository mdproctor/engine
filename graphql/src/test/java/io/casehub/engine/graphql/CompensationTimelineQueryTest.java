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
package io.casehub.engine.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompensationTimelineQueryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CaseQueryResolver resolver;
  private CaseInstanceRepository instanceRepository;
  private CaseDefinitionRegistry definitionRegistry;
  private CaseHubRuntime runtime;
  private PlanItemStore planItemStore;
  private CurrentPrincipal currentPrincipal;

  @BeforeEach
  void setUp() {
    resolver = new CaseQueryResolver();
    instanceRepository = mock(CaseInstanceRepository.class);
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    runtime = mock(CaseHubRuntime.class);
    planItemStore = mock(PlanItemStore.class);
    currentPrincipal = mock(CurrentPrincipal.class);

    resolver.instanceRepository = instanceRepository;
    resolver.definitionRegistry = definitionRegistry;
    resolver.runtime = runtime;
    resolver.planItemStore = planItemStore;
    resolver.currentPrincipal = currentPrincipal;

    when(currentPrincipal.tenancyId()).thenReturn("test-tenant");
  }

  @Test
  void compensationTimelineReturnsNullForNonCompensatingCase() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createInstance(caseId, CaseStatus.COMPLETED);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);
    when(runtime.eventLog(eq(caseId), any(Set.class))).thenReturn(List.of());

    var result = resolver.compensationTimeline(caseId);

    assertThat(result).isNull();
  }

  @Test
  void compensationTimelineReturnsNullForUnknownCase() {
    UUID caseId = UUID.randomUUID();
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(null);

    var result = resolver.compensationTimeline(caseId);

    assertThat(result).isNull();
  }

  @Test
  void compensationTimelineReturnsTimelineForCompensatingCase() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createInstance(caseId, CaseStatus.COMPENSATING);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("case")
            .version("1.0")
            .bindings(
                Binding.builder()
                    .name("review")
                    .judgment(JudgmentTarget.builder().prompt("Review").title("Review").build())
                    .on(new ContextChangeTrigger("$"))
                    .compensateRef("undo-review")
                    .build(),
                Binding.builder()
                    .name("undo-review")
                    .judgment(JudgmentTarget.builder().prompt("Undo").title("Undo").build())
                    .on(new ContextChangeTrigger("$"))
                    .compensation(true)
                    .build())
            .build();
    CaseMetaModel meta = instance.getCaseMetaModel();
    when(definitionRegistry.findByIdentity(meta.getNamespace(), meta.getName(), meta.getVersion()))
        .thenReturn(Optional.of(meta));
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);

    Instant now = Instant.now();
    PlanItemRecord forwardItem =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "review",
            TaskStatus.COMPLETED,
            now.minusSeconds(300),
            TargetType.JUDGMENT,
            null,
            "test-tenant",
            "Review",
            null,
            null);
    PlanItemRecord compensationItem =
        PlanItemRecord.primitive(
            caseId,
            "pi-2",
            "undo-review",
            TaskStatus.RUNNING,
            now.minusSeconds(10),
            TargetType.JUDGMENT,
            null,
            "test-tenant",
            "Undo review",
            null,
            null);
    when(planItemStore.findByCaseId(caseId, "test-tenant"))
        .thenReturn(List.of(forwardItem, compensationItem));

    ObjectNode startedMeta = MAPPER.createObjectNode();
    startedMeta.put("triggeredBy", "operator");
    startedMeta.put("reason", "Clinical trial withdrawn");
    CaseEventLogRecord startedEvent =
        new CaseEventLogRecord(
            CaseHubEventType.COMPENSATION_STARTED,
            EventStreamType.CASE,
            now.minusSeconds(30),
            null,
            startedMeta);
    when(runtime.eventLog(eq(caseId), any(Set.class))).thenReturn(List.of(startedEvent));

    var result = resolver.compensationTimeline(caseId);

    assertThat(result).isNotNull();
    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.status()).isEqualTo("COMPENSATING");
    assertThat(result.triggeredBy()).isEqualTo("operator");
    assertThat(result.reason()).isEqualTo("Clinical trial withdrawn");
    assertThat(result.forwardSteps()).hasSize(1);
    assertThat(result.forwardSteps().get(0).bindingName()).isEqualTo("review");
    assertThat(result.forwardSteps().get(0).status()).isEqualTo("COMPLETED");
    assertThat(result.compensationSteps()).hasSize(1);
    assertThat(result.compensationSteps().get(0).bindingName()).isEqualTo("undo-review");
    assertThat(result.compensationSteps().get(0).status()).isEqualTo("RUNNING");
    assertThat(result.compensationSteps().get(0).compensatesBinding()).isEqualTo("review");
  }

  @Test
  void compensationTimelineIncludesCompletionTimestamp() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createInstance(caseId, CaseStatus.COMPENSATED);
    when(instanceRepository.findByUuid(caseId, "test-tenant")).thenReturn(instance);

    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("case").version("1.0").build();
    CaseMetaModel meta = instance.getCaseMetaModel();
    when(definitionRegistry.findByIdentity(meta.getNamespace(), meta.getName(), meta.getVersion()))
        .thenReturn(Optional.of(meta));
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
    when(planItemStore.findByCaseId(caseId, "test-tenant")).thenReturn(List.of());

    Instant now = Instant.now();
    CaseEventLogRecord startedEvent =
        new CaseEventLogRecord(
            CaseHubEventType.COMPENSATION_STARTED,
            EventStreamType.CASE,
            now.minusSeconds(60),
            null,
            null);
    CaseEventLogRecord completedEvent =
        new CaseEventLogRecord(
            CaseHubEventType.COMPENSATION_COMPLETED, EventStreamType.CASE, now, null, null);
    when(runtime.eventLog(eq(caseId), any(Set.class)))
        .thenReturn(List.of(startedEvent, completedEvent));

    var result = resolver.compensationTimeline(caseId);

    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo("COMPENSATED");
    assertThat(result.compensationStartedAt()).isEqualTo(now.minusSeconds(60));
    assertThat(result.compensationCompletedAt()).isEqualTo(now);
  }

  private CaseInstance createInstance(UUID caseId, CaseStatus status) {
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("test");
    meta.setName("case");
    meta.setVersion("1.0");
    meta.setCreatedAt(Instant.now());

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(status);
    instance.setCaseMetaModel(meta);
    instance.setActorId("test-actor");
    return instance;
  }
}
