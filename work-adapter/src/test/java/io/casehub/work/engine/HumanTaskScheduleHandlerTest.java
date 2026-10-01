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

import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.spi.HumanTaskScheduleRequest;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.persistence.memory.InMemoryPlanItemStore;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemStore;
import io.casehub.work.memory.InMemoryWorkItemStore;
import io.casehub.work.memory.InMemoryWorkItemTemplateStore;
import io.casehub.work.runtime.model.WorkItemTemplate;
import io.casehub.work.runtime.repository.WorkItemTemplateStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies HumanTaskScheduleHandler logic by invoking the handler directly (direct SPI call), so
 * all assertions are synchronous — no await or sleep needed.
 *
 * <p>Refs engine#290 (removed Thread.sleep), engine#291 (fixed detached entity in
 * templateMode_withInputData), engine#245.
 */
@QuarkusTest
class HumanTaskScheduleHandlerTest {
  private static final String TENANCY_ID = "test-tenant";

  @Inject HumanTaskScheduleHandler handler;
  @Inject BlackboardRegistry registry;
  @Inject WorkItemStore workItemStore;
  @Inject WorkItemTemplateStore templateStore;
  @Inject PlanItemStore planItemStore;

  private UUID caseId;
  private PlanItem planItem;

  @BeforeEach
  void setUp() {
    if (workItemStore instanceof InMemoryWorkItemStore mem) {
      mem.clear();
    }
    if (planItemStore instanceof InMemoryPlanItemStore mem) {
      mem.clear();
    }
    if (templateStore instanceof InMemoryWorkItemTemplateStore mem) {
      mem.clear();
    }
    caseId = UUID.randomUUID();
    planItem =
        PlanItem.create("irb-binding", io.casehub.api.model.ExecutorRef.of("unused-worker"), 5);
    assertThat(planItem.tryMarkDispatching()).isTrue();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(planItem);
  }

  @AfterEach
  void tearDown() {
    registry.evict(caseId);
  }

  // ── Inline mode ───────────────────────────────────────────────────────────

  @Test
  void inlineMode_createsWorkItem_withCallerRef_andMarksPlanItemRunning() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("IRB Ethics Review")
            .candidateGroups(Set.of("ethics-committee"))
            .expiresIn(Duration.ofHours(72))
            .build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of("caseRef", "T-42"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.status()).isEqualTo(WorkItemStatus.PENDING);
    assertThat(created.title()).isEqualTo("IRB Ethics Review");
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == TaskStatus.DELEGATED);
  }

  // ── Template mode ─────────────────────────────────────────────────────────

  @Test
  void templateMode_byUuid_createsWorkItem_andMarksPlanItemRunning() {
    WorkItemTemplate tmpl = persistTemplate("IRB Ethics Review Template");

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.status()).isEqualTo(WorkItemStatus.PENDING);
    assertThat(created.title()).isEqualTo("IRB Ethics Review Template");
    assertThat(created.assigneeId()).isNull(); // callerRef must not be passed as assigneeIdOverride
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == TaskStatus.DELEGATED);
  }

  @Test
  void templateMode_withInputData_inputDataOverridesTemplateDefaultPayload() {
    WorkItemTemplate tmpl = persistTemplate("Clinical Trial Consent", "{\"type\":\"default\"}");

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of("trialId", "T-99", "phase", "III"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.payload()).contains("trialId").contains("T-99");
    assertThat(created.payload()).contains("\"type\":\"default\"");
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == TaskStatus.DELEGATED);
  }

  @Test
  void templateMode_emptyInputData_usesTemplateDefaultPayload() {
    WorkItemTemplate tmpl = persistTemplate("Loan Approval", "{\"type\":\"loan\"}");

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.payload()).isEqualTo("{\"type\":\"loan\"}");
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == TaskStatus.DELEGATED);
  }

  @Test
  void templateMode_templateNotFound_planItemStaysPending() {
    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(UUID.randomUUID().toString()).build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }

  @Test
  void templateMode_invalidUuidRef_planItemStaysPending() {
    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template("not-a-uuid").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }

  // ── Case budget deadline bounding ─────────────────────────────────────────

  @Test
  void inlineMode_caseBudgetDeadlineTighter_workItemExpiresAtBudget() {
    Instant budgetDeadline = Instant.now().plusSeconds(600);
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Budget-bounded Review")
            .expiresIn(Duration.ofHours(72))
            .build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            budgetDeadline,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.expiresAt()).isNotNull();
    assertThat(created.expiresAt()).isBeforeOrEqualTo(budgetDeadline.plusSeconds(1));
  }

  @Test
  void inlineMode_taskDeadlineTighter_workItemExpiresAtTask() {
    Instant budgetDeadline = Instant.now().plusSeconds(7200);
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Task-bounded Review")
            .expiresIn(Duration.ofMinutes(30))
            .build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            budgetDeadline,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.expiresAt()).isNotNull();
    assertThat(created.expiresAt()).isBefore(budgetDeadline);
  }

  @Test
  void inlineMode_noCaseBudget_taskDeadlineUsedAsIs() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Unbounded Review").expiresIn(Duration.ofHours(48)).build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  // ── expiresAtDeadline — inline mode ───────────────────────────────────────

  @Test
  void inlineMode_expiresAtDeadline_setsWorkItemExpiresAt() {
    Instant deadline = Instant.now().plusSeconds(3600);
    HumanTaskTarget target = HumanTaskTarget.inline().title("IND Filing").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            deadline,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt()).isEqualTo(deadline);
  }

  @Test
  void inlineMode_expiresAtDeadline_earlierThanExpiresIn_wins() {
    Instant absoluteDeadline = Instant.now().plusSeconds(3600); // 1h
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("IND Filing")
            .expiresIn(Duration.ofDays(7)) // 7d — later
            .build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            absoluteDeadline,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt()).isEqualTo(absoluteDeadline);
  }

  @Test
  void inlineMode_caseBudgetDeadline_earlierThanExpiresAtDeadline_wins() {
    Instant absoluteDeadline = Instant.now().plusSeconds(7200); // 2h — later
    Instant budgetDeadline = Instant.now().plusSeconds(3600); // 1h — earlier
    HumanTaskTarget target = HumanTaskTarget.inline().title("IND Filing").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            budgetDeadline,
            absoluteDeadline,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt()).isEqualTo(budgetDeadline);
  }

  @Test
  void inlineMode_nullExpiresAtDeadline_fallsBackToExpiresIn() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("IND Filing").expiresIn(Duration.ofHours(24)).build();

    Instant before = Instant.now().plusSeconds(23 * 3600);
    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    Instant after = Instant.now().plusSeconds(25 * 3600);

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt()).isBetween(before, after);
  }

  // ── Edge cases ────────────────────────────────────────────────────────────

  @Test
  void noPlanForCaseId_eventIgnored() {
    UUID unknownCaseId = UUID.randomUUID();

    handler.schedule(
        new HumanTaskScheduleRequest(
            unknownCaseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.inline().title("Review").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DISPATCHING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }

  @Test
  void noPlanItemForBindingName_eventIgnored() {
    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "unknown-binding",
            HumanTaskTarget.inline().title("Review").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DISPATCHING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }

  // ── Scope propagation ─────────────────────────────────────────────────────

  @Test
  void inlineMode_withScope_workItemScopeSet() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("IRB Ethics Review")
            .scope("casehubio/clinical/adverse-event")
            .build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope()).isEqualTo("casehubio/clinical/adverse-event");
  }

  @Test
  void inlineMode_withoutScope_workItemScopeIsNull() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("IRB Ethics Review").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope()).isNull();
  }

  @Test
  void templateMode_withScope_workItemScopeSet() {
    WorkItemTemplate tmpl = persistTemplate("IRB Template");

    HumanTaskTarget target =
        HumanTaskTarget.template(tmpl.id.toString())
            .scope("casehubio/clinical/adverse-event")
            .build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope()).isEqualTo("casehubio/clinical/adverse-event");
  }

  @Test
  void templateMode_withoutScope_workItemScopeIsNull() {
    WorkItemTemplate tmpl = persistTemplate("IRB Template");

    HumanTaskTarget target = HumanTaskTarget.template(tmpl.id.toString()).build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope()).isNull();
  }

  // ── resolvedScope overrides static scope ─────────────────────────────────

  @Test
  void inlineMode_resolvedScope_overridesStaticScope() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").scope("static-scope").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "dynamic-scope",
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope()).isEqualTo("dynamic-scope");
  }

  @Test
  void inlineMode_nullResolvedScope_fallsBackToStaticScope() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").scope("static-scope").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope()).isEqualTo("static-scope");
  }

  @Test
  void templateMode_resolvedScope_overridesStaticScope() {
    WorkItemTemplate tmpl = persistTemplate("Template");

    HumanTaskTarget target =
        HumanTaskTarget.template(tmpl.id.toString()).scope("static-scope").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "dynamic-scope",
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope()).isEqualTo("dynamic-scope");
  }

  // ── resolvedTitle overrides static title ────────────────────────────────

  @Test
  void inlineMode_resolvedTitle_overridesStaticTitle() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Static Title").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            "Dynamic Title",
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.title()).isEqualTo("Dynamic Title");
  }

  @Test
  void inlineMode_nullResolvedTitle_fallsBackToStaticTitle() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Static Title").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.title()).isEqualTo("Static Title");
  }

  @Test
  void templateMode_resolvedTitle_overridesStaticTitle() {
    WorkItemTemplate tmpl = persistTemplate("Template");

    HumanTaskTarget target =
        HumanTaskTarget.template(tmpl.id.toString()).title("Static Title").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            "Dynamic Title",
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.title()).isEqualTo("Dynamic Title");
  }

  // ── Dynamic candidateGroups — inline mode ────────────────────────────────

  @Test
  void inlineMode_resolvedCandidateGroups_passedToWorkItem() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("IRB Review").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            Set.of("irb-committee"),
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateGroups()).isEqualTo("irb-committee");
  }

  @Test
  void inlineMode_nullResolvedCandidateGroups_workItemHasNullGroups() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Open Review").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateGroups()).isNull();
  }

  // ── Dynamic candidateGroups — template mode ───────────────────────────────

  @Test
  void templateMode_resolvedCandidateGroups_overridesTemplateDefaults() {
    WorkItemTemplate tmpl = persistTemplate("IRB Review Template");

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            Set.of("committee-a", "committee-b"),
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> w.callerRef() != null && w.callerRef().startsWith("case:"))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateGroups().split(","))
        .containsExactlyInAnyOrder("committee-a", "committee-b");
  }

  @Test
  void templateMode_nullResolvedCandidateGroups_keepsTemplateDefaults() {
    WorkItemTemplate tmpl = persistTemplate("IRB Review Template");

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            null,
            // spec absent — do not override template defaults
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> w.callerRef() != null && w.callerRef().startsWith("case:"))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    // Template has no candidateGroups set (persistTemplate doesn't set them) — stays null
    assertThat(created.candidateGroups()).isNull();
  }

  // ── Dynamic candidateUsers — inline mode ──────────────────────────────────

  @Test
  void inlineMode_resolvedCandidateUsers_passedToWorkItem() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("IRB Review").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            Set.of("user-a"),
            null,
            null,
            null,
            null,
            null,
            null));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateUsers()).isEqualTo("user-a");
  }

  // ── Dynamic candidateUsers — template mode ────────────────────────────────

  @Test
  void templateMode_resolvedCandidateUsers_overridesTemplateDefaults() {
    WorkItemTemplate tmpl = persistTemplate("IRB Review Template");

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            null,
            Set.of("user-x", "user-y"),
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> w.callerRef() != null && w.callerRef().startsWith("case:"))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateUsers().split(",")).containsExactlyInAnyOrder("user-x", "user-y");
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  @Test
  void inlineMode_skipsWorkItemCreation_whenPlanItemAlreadyRunning() {
    // The handler guard (!= PENDING) protects against duplicate call delivery —
    // at-least-once semantics mean the same HumanTaskScheduleRequest could
    // theoretically be dispatched twice. A DELEGATED PlanItem means WorkItem was already
    // created; the handler must be a no-op in that case.
    planItem.markDelegated();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.inline().title("Review").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    assertThat(workItemStore.scanAll())
        .as("handler must not create a WorkItem when PlanItem is already RUNNING")
        .isEmpty();
  }

  // ── Routing context threading ──────────────────────────────────────

  @Test
  void inlineMode_withCandidateScoresAndExperiences_threadsToWorkItem() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Scored Review").build();
    Map<String, Double> scores = Map.of("alice", 0.85, "bob", 0.72);
    List<RetrievedExperience> experiences =
        List.of(
            new RetrievedExperience(
                "similar case",
                "applied plan A",
                "COMPLETED",
                0.9,
                0.85,
                Map.of("domain", "clinical"),
                List.of(),
                Map.of()));

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            experiences,
            scores));

    String expectedCallerRef = PlanItemRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef()))
            .findFirst()
            .orElseThrow();
    assertThat(created.candidateScores()).isNotNull();
    assertThat(created.candidateScores()).contains("alice").contains("0.85");
    assertThat(created.candidateScores()).contains("bob").contains("0.72");
    assertThat(created.routingExperiences()).isNotNull();
    assertThat(created.routingExperiences()).contains("similar case").contains("applied plan A");
  }

  @Test
  void inlineMode_nullScoresAndExperiences_workItemFieldsAreNull() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("No Routing Context").build();

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElseThrow();
    assertThat(created.candidateScores()).isNull();
    assertThat(created.routingExperiences()).isNull();
  }

  @Test
  void templateMode_withCandidateScoresAndExperiences_threadsToWorkItem() {
    WorkItemTemplate tmpl = persistTemplate("Scored Template");
    Map<String, Double> scores = Map.of("charlie", 0.95);
    List<RetrievedExperience> experiences =
        List.of(
            new RetrievedExperience(
                "past case", "used plan B", "FAULTED", 0.3, 0.60, Map.of(), List.of(), Map.of()));

    handler.schedule(
        new HumanTaskScheduleRequest(
            caseId,
            TENANCY_ID,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            experiences,
            scores));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElseThrow();
    assertThat(created.candidateScores()).contains("charlie").contains("0.95");
    assertThat(created.routingExperiences()).contains("past case").contains("used plan B");
  }

  WorkItemTemplate persistTemplate(final String name) {
    return persistTemplate(name, null);
  }

  WorkItemTemplate persistTemplate(final String name, final String defaultPayload) {
    WorkItemTemplate t = new WorkItemTemplate();
    t.name = name;
    t.createdBy = "test";
    t.tenancyId = TenancyConstants.DEFAULT_TENANT_ID;
    t.defaultPayload = defaultPayload;
    return templateStore.put(t);
  }
}
