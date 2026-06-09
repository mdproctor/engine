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
package io.casehub.engine.internal.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.ContextPanel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EpisodicPanelIntraCaseTest {

  @Test
  void initBaseline_setsEmptyLists() {
    CaseContextImpl ctx = new CaseContextImpl();
    EpisodicPanelUpdater.initBaseline(ctx);

    var workers = ctx.writablePanel(ContextPanel.EPISODIC).getList("workers", Map.class);
    var milestones = ctx.writablePanel(ContextPanel.EPISODIC).getList("milestones", String.class);
    var goals = ctx.writablePanel(ContextPanel.EPISODIC).getList("goals", String.class);
    assertNotNull(workers);
    assertEquals(0, workers.size());
    assertNotNull(milestones);
    assertEquals(0, milestones.size());
    assertNotNull(goals);
    assertEquals(0, goals.size());
  }

  @Test
  void recordWorkerCompletion_addsEntry() {
    CaseContextImpl ctx = new CaseContextImpl();
    EpisodicPanelUpdater.initBaseline(ctx);

    EpisodicPanelUpdater.recordWorkerCompletion(ctx, "extractor", "COMPLETED");

    var workers = ctx.writablePanel(ContextPanel.EPISODIC).getList("workers", Map.class);
    assertEquals(1, workers.size());
    assertEquals("extractor", workers.get(0).get("name"));
    assertEquals("COMPLETED", workers.get(0).get("lastOutcome"));
    assertEquals(1, ((Number) workers.get(0).get("runs")).intValue());
  }

  @Test
  void recordWorkerCompletion_incrementsRuns() {
    CaseContextImpl ctx = new CaseContextImpl();
    EpisodicPanelUpdater.initBaseline(ctx);

    EpisodicPanelUpdater.recordWorkerCompletion(ctx, "extractor", "COMPLETED");
    EpisodicPanelUpdater.recordWorkerCompletion(ctx, "extractor", "COMPLETED");

    var workers = ctx.writablePanel(ContextPanel.EPISODIC).getList("workers", Map.class);
    assertEquals(1, workers.size()); // same worker, not duplicated
    assertEquals(2, ((Number) workers.get(0).get("runs")).intValue());
  }

  @Test
  void recordMilestoneReached_appendsName() {
    CaseContextImpl ctx = new CaseContextImpl();
    EpisodicPanelUpdater.initBaseline(ctx);

    EpisodicPanelUpdater.recordMilestoneReached(ctx, "data-ready");

    var milestones = ctx.writablePanel(ContextPanel.EPISODIC).getList("milestones", String.class);
    assertTrue(milestones.contains("data-ready"));
  }

  @Test
  void recordMilestoneReached_notDuplicated() {
    CaseContextImpl ctx = new CaseContextImpl();
    EpisodicPanelUpdater.initBaseline(ctx);

    EpisodicPanelUpdater.recordMilestoneReached(ctx, "data-ready");
    EpisodicPanelUpdater.recordMilestoneReached(ctx, "data-ready");

    var milestones = ctx.writablePanel(ContextPanel.EPISODIC).getList("milestones", String.class);
    assertEquals(1, milestones.stream().filter("data-ready"::equals).count());
  }

  @Test
  void episodicUpdates_doNotBumpWorkingVersion() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.set("result", "done");
    long workingVersionBefore = ctx.getVersion();

    EpisodicPanelUpdater.initBaseline(ctx);
    EpisodicPanelUpdater.recordWorkerCompletion(ctx, "worker1", "COMPLETED");

    assertEquals(workingVersionBefore, ctx.getVersion()); // working version unchanged
  }
}
