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
package io.casehub.work.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.engine.runtime.context.CaseContextImpl;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlanItemCompletionApplierTest {

  @Inject BlackboardRegistry registry;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject CaseInstanceRepository caseInstanceRepository;
  @Inject PlanItemCompletionApplier applier;

  private UUID caseId;
  private PlanItem planItem;
  private String planItemId;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();
    planItem =
        PlanItem.create(
            "escalation-binding",
            io.casehub.api.model.ExecutorRef.of("ht-worker"),
            10,
            io.casehub.api.model.JudgmentTarget.builder().prompt("Review").title("Review").build());
    planItem.tryMarkDispatching();
    planItem.markDelegated();
    planItemId = planItem.getPlanItemId();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(planItem);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(io.casehub.api.model.CaseStatus.RUNNING);
    instance.setCaseContext(new CaseContextImpl(Map.of("stage", "review")));
    caseInstanceRepository.save(instance, "test-tenant");
  }

  @AfterEach
  void tearDown() {
    registry.evict(caseId);
  }

  @Test
  void escalated_marksFaulted_writesSignal_skipsOutputMapping() {
    UUID workItemId = UUID.randomUUID();
    WorkItemRef ref =
        new WorkItemRef(
            workItemId,
            WorkItemStatus.ESCALATED,
            PlanItemRef.encode(caseId, planItemId),
            null,
            null,
            "committee-a,committee-b",
            null,
            "test-tenant",
            null,
            null,
            null,
            null);

    applier.apply(caseId, planItemId, WorkItemStatus.ESCALATED, ref, null);

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.FAULTED);

    CaseInstance updated = caseInstanceRepository.findByUuid(caseId, "test-tenant").orElse(null);
    Object signal = updated.getCaseContext().get("workItemEscalated");
    assertThat(signal).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> signalMap = (Map<String, Object>) signal;
    assertThat(signalMap)
        .containsEntry("workItemId", workItemId.toString())
        .containsEntry("bindingName", "escalation-binding");
    assertThat(signalMap.get("lastCandidateGroups"))
        .asList()
        .containsExactlyInAnyOrder("committee-a", "committee-b");

    assertThat(updated.getCaseContext().get("stage")).isEqualTo("review");
  }

  @Test
  void escalated_withStaleResolution_bypassesValidation_stillTransitions() {
    UUID workItemId = UUID.randomUUID();
    WorkItemRef ref =
        new WorkItemRef(
            workItemId,
            WorkItemStatus.ESCALATED,
            PlanItemRef.encode(caseId, planItemId),
            null,
            "{\"partial\": \"data\"}",
            "committee-a",
            null,
            "test-tenant",
            null,
            null,
            "io.casehub.SomeResolutionType",
            null);

    applier.apply(caseId, planItemId, WorkItemStatus.ESCALATED, ref, null);

    assertThat(planItem.getStatus())
        .as("ESCALATED must bypass resolution validation")
        .isEqualTo(TaskStatus.FAULTED);

    CaseInstance updated = caseInstanceRepository.findByUuid(caseId, "test-tenant").orElse(null);
    Object signal = updated.getCaseContext().get("workItemEscalated");
    assertThat(signal).isNotNull();
  }

  @Test
  void completed_marksPlanItemCompleted() {
    UUID workItemId = UUID.randomUUID();
    WorkItemRef ref =
        new WorkItemRef(
            workItemId,
            WorkItemStatus.COMPLETED,
            PlanItemRef.encode(caseId, planItemId),
            null,
            "{\"approved\": true}",
            null,
            null,
            "test-tenant",
            null,
            null,
            null,
            null);

    applier.apply(caseId, planItemId, WorkItemStatus.COMPLETED, ref, null);

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void completed_withOutputMapping_appliesResolutionToContext() {
    JudgmentTarget target =
        JudgmentTarget.builder()
            .prompt("Review judgment")
            .title("Review")
            .outputMapping("{ approved: .approved }")
            .build();

    UUID outputCaseId = UUID.randomUUID();
    PlanItem outputItem =
        PlanItem.create(
            "judgment-binding", io.casehub.api.model.ExecutorRef.of("judgment-worker"), 10, target);
    outputItem.tryMarkDispatching();
    outputItem.markDelegated();
    String outputPlanItemId = outputItem.getPlanItemId();
    registry.getOrCreate(outputCaseId, "test-tenant").addPlanItem(outputItem);

    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("judgment-output-test")
            .version("1.0.0")
            .binding(Binding.builder().name("judgment-binding").judgment(target).on("true").build())
            .build();
    CaseMetaModel metaModel = caseDefinitionRegistry.registerCaseDefinition(def);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(outputCaseId);
    instance.setState(io.casehub.api.model.CaseStatus.RUNNING);
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl(Map.of("stage", "review")));
    caseInstanceRepository.save(instance, "test-tenant");

    WorkItemRef ref =
        new WorkItemRef(
            UUID.randomUUID(),
            WorkItemStatus.COMPLETED,
            PlanItemRef.encode(outputCaseId, outputPlanItemId),
            null,
            "{\"approved\": true}",
            null,
            null,
            "test-tenant",
            null,
            null,
            null,
            null);

    applier.apply(outputCaseId, outputPlanItemId, WorkItemStatus.COMPLETED, ref, null);

    assertThat(outputItem.getStatus()).isEqualTo(TaskStatus.COMPLETED);

    CaseInstance updated =
        caseInstanceRepository.findByUuid(outputCaseId, "test-tenant").orElseThrow();
    assertThat(updated.getCaseContext().get("approved"))
        .as("outputMapping must apply resolution to CaseContext — Refs #1167")
        .isEqualTo(true);

    registry.evict(outputCaseId);
  }
}
