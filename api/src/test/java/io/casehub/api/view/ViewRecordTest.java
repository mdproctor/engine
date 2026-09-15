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
package io.casehub.api.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ViewRecordTest {

  @Test
  void caseInstanceView_holdsAllFields() {
    var id = UUID.randomUUID();
    var now = Instant.now();
    var view = new CaseInstanceView(id, CaseStatus.RUNNING, "acme", "order", "1.0.0", now, "alice");
    assertEquals(id, view.caseId());
    assertEquals(CaseStatus.RUNNING, view.status());
    assertEquals("acme", view.namespace());
    assertEquals("order", view.name());
    assertEquals("1.0.0", view.version());
    assertEquals(now, view.createdAt());
    assertEquals("alice", view.actorId());
  }

  @Test
  void casePage_wrapsItemsWithPagination() {
    var item =
        new CaseInstanceView(
            UUID.randomUUID(), CaseStatus.RUNNING, "ns", "n", "1.0", Instant.now(), "a");
    var page = new CasePage(List.of(item), 42, true);
    assertEquals(1, page.items().size());
    assertEquals(42, page.totalCount());
    assertTrue(page.hasMore());
  }

  @Test
  void caseDefinitionPage_wrapsDefinitions() {
    var def =
        new CaseDefinitionView("ns", "name", "1.0", "Title", "Summary", List.of("cap1", "cap2"));
    var page = new CaseDefinitionPage(List.of(def), 1, false);
    assertEquals("ns", page.items().get(0).namespace());
    assertEquals(2, page.items().get(0).capabilities().size());
  }

  @Test
  void goalEvaluationView_holdsGoalsAndSummary() {
    var goal = new GoalStatusView("g1", "SIMPLE", true, ".done");
    var summary = new CompletionSummaryView(false, 1, 3, "GOAL_BASED");
    var eval = new GoalEvaluationView(List.of(goal), summary);
    assertEquals(1, eval.goals().size());
    assertTrue(eval.goals().get(0).reached());
    assertFalse(eval.completionSummary().complete());
    assertEquals(1, eval.completionSummary().satisfied());
  }

  @Test
  void caseControlView_holdsCaseIdAndStatus() {
    var id = UUID.randomUUID();
    var view = new CaseControlView(id, CaseStatus.SUSPENDED);
    assertEquals(id, view.caseId());
    assertEquals(CaseStatus.SUSPENDED, view.status());
  }

  @Test
  void eventLogPage_wrapsPaginatedEntries() {
    var entry =
        new EventLogEntryView("CASE_STARTED", "LIFECYCLE", Instant.now(), Map.of("key", "val"));
    var page = new EventLogPage(List.of(entry), 100, true);
    assertEquals(100, page.totalCount());
    assertEquals("CASE_STARTED", page.items().get(0).eventType());
  }

  @Test
  void planItemView_holdsFields() {
    var id = UUID.randomUUID();
    var parentId = UUID.randomUUID();
    var view = new PlanItemView(id, "step1", TaskStatus.PENDING, "WORKER", parentId);
    assertEquals("step1", view.name());
    assertEquals(TaskStatus.PENDING, view.status());
    assertEquals(parentId, view.parentId());
  }

  @Test
  void streamEventViews_holdFields() {
    var caseId = UUID.randomUUID();
    var stream = new CaseStreamEventView(caseId, "PLAN_ITEM_CHANGED", Map.of("status", "RUNNING"));
    assertEquals(caseId, stream.caseId());

    var lifecycle =
        new CaseLifecycleEventView(
            caseId, "STARTED", "START", "RUNNING", "alice", "HUMAN", "order", "acme", null, null);
    assertEquals("STARTED", lifecycle.eventType());

    var contextChange = new CaseContextChangeEventView(caseId, "WORKING", Map.of("key", "value"));
    assertEquals("WORKING", contextChange.changedLayer());
  }

  @Test
  void requestRecords_holdFields() {
    var start = new StartCaseRequest("ns", "name", "1.0", Map.of("k", "v"));
    assertEquals("ns", start.namespace());

    var control = new CaseControlRequest("maintenance");
    assertEquals("maintenance", control.reason());

    var signal = new SendSignalRequest(".path", "value");
    assertEquals(".path", signal.path());
  }
}
