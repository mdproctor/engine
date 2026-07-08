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
import static org.awaitility.Awaitility.await;

import io.casehub.api.model.HumanTaskTarget;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.HumanTaskScheduleEvent;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.persistence.memory.InMemoryPlanItemStore;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.memory.InMemoryWorkItemStore;
import io.casehub.work.memory.InMemoryWorkItemTemplateStore;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemTemplate;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.casehub.work.runtime.repository.WorkItemTemplateStore;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies HumanTaskScheduleHandler logic by invoking the handler directly (no event bus dispatch),
 * so all assertions are synchronous — no await or sleep needed. A single wiring test confirms
 * the @ConsumeEvent route is correctly registered.
 *
 * <p>Refs engine#290 (removed Thread.sleep), engine#291 (fixed detached entity in
 * templateMode_withInputData), engine#245.
 */
@QuarkusTest
class HumanTaskScheduleHandlerTest {
  private static final String TENANCY_ID = "test-tenant";

  @Inject HumanTaskScheduleHandler handler;
  @Inject BlackboardRegistry registry;
  @Inject EventBus eventBus;
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
    planItem = PlanItem.create("irb-binding", "unused-worker", 5);
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(planItem);
  }

  @AfterEach
  void tearDown() {
    registry.evict(caseId);
  }

  // ── Wiring ────────────────────────────────────────────────────────────────

  @Test
  void eventBus_routesHumanTaskScheduleEvent_toHandler() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Smoke").build();
    eventBus.publish(
        EventBusAddresses.HUMAN_TASK_SCHEDULE,
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.DELEGATED));
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

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            target,
            Map.of("caseRef", "T-42"),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.status).isEqualTo(WorkItemStatus.PENDING);
    assertThat(created.title).isEqualTo("IRB Ethics Review");
    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == PlanItemStatus.DELEGATED);
  }

  // ── Template mode ─────────────────────────────────────────────────────────

  @Test
  void templateMode_byUuid_createsWorkItem_andMarksPlanItemRunning() {
    WorkItemTemplate tmpl = persistTemplate("IRB Ethics Review Template");

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.status).isEqualTo(WorkItemStatus.PENDING);
    assertThat(created.title).isEqualTo("IRB Ethics Review Template");
    assertThat(created.assigneeId).isNull(); // callerRef must not be passed as assigneeIdOverride
    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == PlanItemStatus.DELEGATED);
  }

  @Test
  void templateMode_withInputData_inputDataOverridesTemplateDefaultPayload() {
    WorkItemTemplate tmpl = persistTemplate("Clinical Trial Consent", "{\"type\":\"default\"}");

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of("trialId", "T-99", "phase", "III"),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.payload).contains("trialId").contains("T-99");
    assertThat(created.payload).contains("\"type\":\"default\"");
    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == PlanItemStatus.DELEGATED);
  }

  @Test
  void templateMode_emptyInputData_usesTemplateDefaultPayload() {
    WorkItemTemplate tmpl = persistTemplate("Loan Approval", "{\"type\":\"loan\"}");

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.payload).isEqualTo("{\"type\":\"loan\"}");
    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
    assertThat(planItemStore.findByCaseId(caseId, "test-tenant"))
        .anyMatch(
            r ->
                r.planItemId().equals(planItem.getPlanItemId())
                    && r.status() == PlanItemStatus.DELEGATED);
  }

  @Test
  void templateMode_templateNotFound_planItemStaysPending() {
    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template(UUID.randomUUID().toString()).build(),
            Map.of(),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.PENDING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }

  @Test
  void templateMode_invalidUuidRef_planItemStaysPending() {
    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template("not-a-uuid").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.PENDING);
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

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, budgetDeadline, null, TENANCY_ID));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.expiresAt).isNotNull();
    assertThat(created.expiresAt).isBeforeOrEqualTo(budgetDeadline.plusSeconds(1));
  }

  @Test
  void inlineMode_taskDeadlineTighter_workItemExpiresAtTask() {
    Instant budgetDeadline = Instant.now().plusSeconds(7200);
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Task-bounded Review")
            .expiresIn(Duration.ofMinutes(30))
            .build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, budgetDeadline, null, TENANCY_ID));

    WorkItem created = workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.expiresAt).isNotNull();
    assertThat(created.expiresAt).isBefore(budgetDeadline);
  }

  @Test
  void inlineMode_noCaseBudget_taskDeadlineUsedAsIs() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Unbounded Review").expiresIn(Duration.ofHours(48)).build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));

    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
  }

  // ── expiresAtDeadline — inline mode ───────────────────────────────────────

  @Test
  void inlineMode_expiresAtDeadline_setsWorkItemExpiresAt() {
    Instant deadline = Instant.now().plusSeconds(3600);
    HumanTaskTarget target = HumanTaskTarget.inline().title("IND Filing").build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, deadline, TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt).isEqualTo(deadline);
  }

  @Test
  void inlineMode_expiresAtDeadline_earlierThanExpiresIn_wins() {
    Instant absoluteDeadline = Instant.now().plusSeconds(3600); // 1h
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("IND Filing")
            .expiresIn(Duration.ofDays(7)) // 7d — later
            .build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            null,
            absoluteDeadline,
            TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt).isEqualTo(absoluteDeadline);
  }

  @Test
  void inlineMode_caseBudgetDeadline_earlierThanExpiresAtDeadline_wins() {
    Instant absoluteDeadline = Instant.now().plusSeconds(7200); // 2h — later
    Instant budgetDeadline = Instant.now().plusSeconds(3600); // 1h — earlier
    HumanTaskTarget target = HumanTaskTarget.inline().title("IND Filing").build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            target,
            Map.of(),
            null,
            null,
            budgetDeadline,
            absoluteDeadline,
            TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt).isEqualTo(budgetDeadline);
  }

  @Test
  void inlineMode_nullExpiresAtDeadline_fallsBackToExpiresIn() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("IND Filing").expiresIn(Duration.ofHours(24)).build();

    Instant before = Instant.now().plusSeconds(23 * 3600);
    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));
    Instant after = Instant.now().plusSeconds(25 * 3600);

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElseThrow();
    assertThat(created.expiresAt).isBetween(before, after);
  }

  // ── Edge cases ────────────────────────────────────────────────────────────

  @Test
  void noPlanForCaseId_eventIgnored() {
    UUID unknownCaseId = UUID.randomUUID();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            unknownCaseId,
            "irb-binding",
            HumanTaskTarget.inline().title("Review").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.PENDING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }

  @Test
  void noPlanItemForBindingName_eventIgnored() {
    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "unknown-binding",
            HumanTaskTarget.inline().title("Review").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.PENDING);
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

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));

    io.casehub.work.runtime.model.WorkItem created =
        workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope).isEqualTo("casehubio/clinical/adverse-event");
  }

  @Test
  void inlineMode_withoutScope_workItemScopeIsNull() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("IRB Ethics Review").build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));

    io.casehub.work.runtime.model.WorkItem created =
        workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope).isNull();
  }

  @Test
  void templateMode_withScope_workItemScopeSet() {
    WorkItemTemplate tmpl = persistTemplate("IRB Template");

    HumanTaskTarget target =
        HumanTaskTarget.template(tmpl.id.toString())
            .scope("casehubio/clinical/adverse-event")
            .build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));

    io.casehub.work.runtime.model.WorkItem created =
        workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope).isEqualTo("casehubio/clinical/adverse-event");
  }

  @Test
  void templateMode_withoutScope_workItemScopeIsNull() {
    WorkItemTemplate tmpl = persistTemplate("IRB Template");

    HumanTaskTarget target = HumanTaskTarget.template(tmpl.id.toString()).build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));

    io.casehub.work.runtime.model.WorkItem created =
        workItemStore.scanAll().stream().findFirst().orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.scope).isNull();
  }

  // ── Dynamic candidateGroups — inline mode ────────────────────────────────

  @Test
  void inlineMode_resolvedCandidateGroups_passedToWorkItem() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("IRB Review").build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            target,
            Map.of(),
            Set.of("irb-committee"),
            null,
            null,
            null,
            TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateGroups).isEqualTo("irb-committee");
  }

  @Test
  void inlineMode_nullResolvedCandidateGroups_workItemHasNullGroups() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Open Review").build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId, "irb-binding", target, Map.of(), null, null, null, null, TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateGroups).isNull();
  }

  // ── Dynamic candidateGroups — template mode ───────────────────────────────

  @Test
  void templateMode_resolvedCandidateGroups_overridesTemplateDefaults() {
    WorkItemTemplate tmpl = persistTemplate("IRB Review Template");

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            Set.of("committee-a", "committee-b"),
            null,
            null,
            null,
            TENANCY_ID));

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> w.callerRef != null && w.callerRef.startsWith("case:"))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateGroups.split(","))
        .containsExactlyInAnyOrder("committee-a", "committee-b");
  }

  @Test
  void templateMode_nullResolvedCandidateGroups_keepsTemplateDefaults() {
    WorkItemTemplate tmpl = persistTemplate("IRB Review Template");

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null, // spec absent — do not override template defaults
            null,
            null,
            null,
            TENANCY_ID));

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> w.callerRef != null && w.callerRef.startsWith("case:"))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    // Template has no candidateGroups set (persistTemplate doesn't set them) — stays null
    assertThat(created.candidateGroups).isNull();
  }

  // ── Dynamic candidateUsers — inline mode ──────────────────────────────────

  @Test
  void inlineMode_resolvedCandidateUsers_passedToWorkItem() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("IRB Review").build();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            target,
            Map.of(),
            null,
            Set.of("user-a"),
            null,
            null,
            TENANCY_ID));

    String expectedCallerRef = PlanItemCallerRef.encode(caseId, planItem.getPlanItemId());
    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateUsers).isEqualTo("user-a");
  }

  // ── Dynamic candidateUsers — template mode ────────────────────────────────

  @Test
  void templateMode_resolvedCandidateUsers_overridesTemplateDefaults() {
    WorkItemTemplate tmpl = persistTemplate("IRB Review Template");

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.template(tmpl.id.toString()).build(),
            Map.of(),
            null,
            Set.of("user-x", "user-y"),
            null,
            null,
            TENANCY_ID));

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> w.callerRef != null && w.callerRef.startsWith("case:"))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.candidateUsers.split(",")).containsExactlyInAnyOrder("user-x", "user-y");
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  @Test
  void inlineMode_skipsWorkItemCreation_whenPlanItemAlreadyRunning() {
    // The handler guard (!= PENDING) protects against duplicate event delivery —
    // at-least-once event bus semantics mean the same HumanTaskScheduleEvent could
    // theoretically be dispatched twice. A DELEGATED PlanItem means WorkItem was already
    // created; the handler must be a no-op in that case.
    planItem.markDelegated();

    handler.onHumanTaskSchedule(
        new HumanTaskScheduleEvent(
            caseId,
            "irb-binding",
            HumanTaskTarget.inline().title("Review").build(),
            Map.of(),
            null,
            null,
            null,
            null,
            TENANCY_ID));

    assertThat(workItemStore.scanAll())
        .as("handler must not create a WorkItem when PlanItem is already RUNNING")
        .isEmpty();
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
