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

import com.fasterxml.jackson.databind.JsonNode;
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
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.graphql.dto.CompensationTimelineType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompensationTimelineAssemblyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TENANT = "test-tenant";

  private CaseQueryResolver resolver;
  private CaseInstanceRepository instanceRepository;
  private CaseDefinitionRegistry definitionRegistry;
  private CaseHubRuntime runtime;
  private PlanItemStore planItemStore;
  private CurrentPrincipal currentPrincipal;

  private UUID caseId;
  private CaseInstance instance;

  @BeforeEach
  void setUp() {
    instanceRepository = mock(CaseInstanceRepository.class);
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    runtime = mock(CaseHubRuntime.class);
    planItemStore = mock(PlanItemStore.class);
    currentPrincipal = mock(CurrentPrincipal.class);

    resolver = new CaseQueryResolver();
    resolver.instanceRepository = instanceRepository;
    resolver.definitionRegistry = definitionRegistry;
    resolver.runtime = runtime;
    resolver.currentPrincipal = currentPrincipal;
    resolver.planItemStore = planItemStore;
    resolver.ledgerRepository = mock(io.casehub.ledger.repository.CaseLedgerEntryRepository.class);

    when(currentPrincipal.tenancyId()).thenReturn(TENANT);

    caseId = UUID.randomUUID();
    instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = TENANT;
    instance.setState(CaseStatus.COMPENSATED);

    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("io.casehub.test");
    meta.setName("test-case");
    meta.setVersion("1.0");
    instance.setCaseMetaModel(meta);

    when(instanceRepository.findByUuid(caseId, TENANT)).thenReturn(instance);
  }

  @Test
  void no_compensation_events_returns_null() {
    when(runtime.eventLog(eq(caseId), any())).thenReturn(List.of());

    CompensationTimelineType result = resolver.compensationTimeline(caseId);

    assertThat(result).isNull();
  }

  @Test
  void single_attempt_groups_all_steps() {
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-01T10:01:00Z");
    Instant t3 = Instant.parse("2026-09-01T10:02:00Z");
    Instant t4 = Instant.parse("2026-09-01T10:03:00Z");

    ObjectNode startMeta = MAPPER.createObjectNode();
    startMeta.put("triggeredBy", "operator-1");
    startMeta.put("reason", "trial withdrawn");

    when(runtime.eventLog(eq(caseId), any()))
        .thenReturn(
            List.of(
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STARTED,
                    EventStreamType.CASE,
                    t1,
                    null,
                    startMeta),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STEP_STARTED,
                    EventStreamType.CASE,
                    t2,
                    null,
                    null),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STEP_COMPLETED,
                    EventStreamType.CASE,
                    t3,
                    null,
                    null),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_COMPLETED,
                    EventStreamType.CASE,
                    t4,
                    null,
                    null)));

    setupDefinition(
        Binding.builder()
            .name("irb-review")
            .judgment(JudgmentTarget.builder().prompt("Review").title("Review").build())
            .on(new ContextChangeTrigger("$"))
            .compensateRef("irb-reversal")
            .build(),
        Binding.builder()
            .name("irb-reversal")
            .judgment(JudgmentTarget.builder().prompt("Reversal").title("Reversal").build())
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build());

    when(planItemStore.findByCaseId(caseId, TENANT))
        .thenReturn(
            List.of(
                planItem(
                    "pi-1",
                    "irb-review",
                    TaskStatus.COMPLETED,
                    t1.minusSeconds(60),
                    t1.minusSeconds(10),
                    null),
                planItem("pi-2", "irb-reversal", TaskStatus.COMPLETED, t2, t3, null)));

    CompensationTimelineType result = resolver.compensationTimeline(caseId);

    assertThat(result).isNotNull();
    assertThat(result.attempts()).hasSize(1);
    assertThat(result.attempts().get(0).attemptNumber()).isEqualTo(1);
    assertThat(result.attempts().get(0).outcome()).isEqualTo("COMPLETED");
    assertThat(result.attempts().get(0).triggeredBy()).isEqualTo("operator-1");
    assertThat(result.attempts().get(0).reason()).isEqualTo("trial withdrawn");
    assertThat(result.attempts().get(0).steps()).hasSize(1);
    assertThat(result.forwardSteps()).hasSize(1);
    assertThat(result.childCompensationCaseIds()).isEmpty();
  }

  @Test
  void two_attempts_groups_steps_by_time_window() {
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-01T10:01:00Z");
    Instant t3 = Instant.parse("2026-09-01T10:02:00Z");
    Instant t4 = Instant.parse("2026-09-01T10:05:00Z");
    Instant t5 = Instant.parse("2026-09-01T10:06:00Z");
    Instant t6 = Instant.parse("2026-09-01T10:07:00Z");

    when(runtime.eventLog(eq(caseId), any()))
        .thenReturn(
            List.of(
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STARTED, EventStreamType.CASE, t1, null, null),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_FAULTED, EventStreamType.CASE, t3, null, null),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STARTED, EventStreamType.CASE, t4, null, null),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_COMPLETED,
                    EventStreamType.CASE,
                    t6,
                    null,
                    null)));

    setupDefinition(
        Binding.builder()
            .name("forward-1")
            .judgment(JudgmentTarget.builder().prompt("Fwd").title("Fwd").build())
            .on(new ContextChangeTrigger("$"))
            .build(),
        Binding.builder()
            .name("comp-1")
            .judgment(JudgmentTarget.builder().prompt("Rev").title("Rev").build())
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build());

    when(planItemStore.findByCaseId(caseId, TENANT))
        .thenReturn(
            List.of(
                planItem(
                    "pi-f",
                    "forward-1",
                    TaskStatus.COMPLETED,
                    t1.minusSeconds(120),
                    t1.minusSeconds(60),
                    null),
                planItem("pi-c1", "comp-1", TaskStatus.FAULTED, t2, t3, null),
                planItem("pi-c2", "comp-1", TaskStatus.COMPLETED, t5, t6, null)));

    CompensationTimelineType result = resolver.compensationTimeline(caseId);

    assertThat(result).isNotNull();
    assertThat(result.attempts()).hasSize(2);
    assertThat(result.attempts().get(0).attemptNumber()).isEqualTo(1);
    assertThat(result.attempts().get(0).outcome()).isEqualTo("FAULTED");
    assertThat(result.attempts().get(0).steps()).hasSize(1);
    assertThat(result.attempts().get(1).attemptNumber()).isEqualTo(2);
    assertThat(result.attempts().get(1).outcome()).isEqualTo("COMPLETED");
    assertThat(result.attempts().get(1).steps()).hasSize(1);
  }

  @Test
  void in_progress_attempt_has_null_completedAt() {
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");

    instance.setState(CaseStatus.COMPENSATING);

    when(runtime.eventLog(eq(caseId), any()))
        .thenReturn(
            List.of(
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STARTED, EventStreamType.CASE, t1, null, null)));

    setupDefinition(
        Binding.builder()
            .name("comp-1")
            .judgment(JudgmentTarget.builder().prompt("Rev").title("Rev").build())
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build());

    when(planItemStore.findByCaseId(caseId, TENANT))
        .thenReturn(
            List.of(
                planItem("pi-c1", "comp-1", TaskStatus.RUNNING, t1.plusSeconds(5), null, null)));

    CompensationTimelineType result = resolver.compensationTimeline(caseId);

    assertThat(result).isNotNull();
    assertThat(result.attempts()).hasSize(1);
    assertThat(result.attempts().get(0).outcome()).isEqualTo("IN_PROGRESS");
    assertThat(result.attempts().get(0).completedAt()).isNull();
  }

  @Test
  void faulted_step_enriched_with_error_from_diagnostics() {
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-01T10:01:00Z");

    ObjectNode diagNode = MAPPER.createObjectNode();
    ObjectNode bindingDiag = MAPPER.createObjectNode();
    ObjectNode latestDiagnosis = MAPPER.createObjectNode();
    latestDiagnosis.put("category", "Knowledge");
    latestDiagnosis.put("reason", "Agent declined: missing access credentials");
    bindingDiag.set("latestDiagnosis", latestDiagnosis);
    diagNode.set("comp-1", bindingDiag);

    when(runtime.query(eq(caseId), eq("_diagnostics"), eq(JsonNode.class))).thenReturn(diagNode);
    when(runtime.eventLog(eq(caseId), any()))
        .thenReturn(
            List.of(
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STARTED, EventStreamType.CASE, t1, null, null),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_FAULTED, EventStreamType.CASE, t2, null, null)));

    setupDefinition(
        Binding.builder()
            .name("comp-1")
            .judgment(JudgmentTarget.builder().prompt("Rev").title("Rev").build())
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build());

    when(planItemStore.findByCaseId(caseId, TENANT))
        .thenReturn(
            List.of(planItem("pi-c1", "comp-1", TaskStatus.FAULTED, t1.plusSeconds(5), t2, null)));

    CompensationTimelineType result = resolver.compensationTimeline(caseId);

    assertThat(result).isNotNull();
    assertThat(result.attempts()).hasSize(1);
    var step = result.attempts().get(0).steps().get(0);
    assertThat(step.errorReason()).isEqualTo("Agent declined: missing access credentials");
    assertThat(step.failureCategory()).isEqualTo("Knowledge");
  }

  @Test
  void child_compensation_case_ids_populated() {
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-09-01T10:05:00Z");
    UUID childCaseId = UUID.randomUUID();

    ObjectNode stepMeta = MAPPER.createObjectNode();
    stepMeta.put("childCaseId", childCaseId.toString());

    when(runtime.eventLog(eq(caseId), any()))
        .thenReturn(
            List.of(
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STARTED, EventStreamType.CASE, t1, null, null),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_STEP_STARTED,
                    EventStreamType.CASE,
                    t1.plusSeconds(10),
                    null,
                    stepMeta),
                new CaseEventLogRecord(
                    CaseHubEventType.COMPENSATION_COMPLETED,
                    EventStreamType.CASE,
                    t2,
                    null,
                    null)));

    setupDefinition(
        Binding.builder()
            .name("comp-1")
            .judgment(JudgmentTarget.builder().prompt("Rev").title("Rev").build())
            .on(new ContextChangeTrigger("$"))
            .compensation(true)
            .build());

    when(planItemStore.findByCaseId(caseId, TENANT))
        .thenReturn(
            List.of(
                planItem("pi-c1", "comp-1", TaskStatus.COMPLETED, t1.plusSeconds(5), t2, null)));

    CompensationTimelineType result = resolver.compensationTimeline(caseId);

    assertThat(result).isNotNull();
    assertThat(result.childCompensationCaseIds()).containsExactly(childCaseId);
  }

  private void setupDefinition(Binding... bindings) {
    CaseDefinition def = mock(CaseDefinition.class);
    when(def.getBindings()).thenReturn(List.of(bindings));
    CaseMetaModel meta = instance.getCaseMetaModel();
    when(definitionRegistry.findByIdentity(meta.getNamespace(), meta.getName(), meta.getVersion()))
        .thenReturn(Optional.of(meta));
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
  }

  private static PlanItemRecord planItem(
      String planItemId,
      String bindingName,
      TaskStatus status,
      Instant createdAt,
      Instant completedAt,
      io.casehub.engine.common.internal.model.TargetType targetType) {
    return PlanItemRecord.primitive(
        UUID.randomUUID(),
        planItemId,
        bindingName,
        status,
        createdAt,
        targetType,
        null,
        "test-tenant",
        null,
        null,
        null);
  }
}
