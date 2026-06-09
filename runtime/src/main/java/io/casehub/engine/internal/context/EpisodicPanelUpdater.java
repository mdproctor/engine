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

import io.casehub.api.context.ContextPanel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EpisodicPanelUpdater {

  private EpisodicPanelUpdater() {}

  /** Initializes the episodic panel baseline: {workers:[], milestones:[], goals:[]} */
  public static void initBaseline(CaseContextImpl ctx) {
    WritablePanelImpl episodic = ctx.writablePanel(ContextPanel.EPISODIC);
    if (!episodic.contains("workers")) episodic.engineSet("workers", new ArrayList<>());
    if (!episodic.contains("milestones")) episodic.engineSet("milestones", new ArrayList<>());
    if (!episodic.contains("goals")) episodic.engineSet("goals", new ArrayList<>());
  }

  /**
   * Updates or creates the worker entry in episodic.workers. Increments the runs counter; sets
   * lastOutcome and lastTimestamp. Safe to call whether or not the episodic panel is frozen —
   * writes use {@link WritablePanelImpl#engineSet} which bypasses the frozen check.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static void recordWorkerCompletion(
      CaseContextImpl ctx, String workerName, String outcome) {
    WritablePanelImpl episodic = ctx.writablePanel(ContextPanel.EPISODIC);
    List<Map<String, Object>> workers =
        (List<Map<String, Object>>) (List) episodic.getList("workers", Map.class);
    if (workers == null) workers = new ArrayList<>();
    else workers = new ArrayList<>(workers);

    Map<String, Object> entry = null;
    for (Map<String, Object> w : workers) {
      if (workerName.equals(w.get("name"))) {
        entry = w;
        break;
      }
    }

    if (entry == null) {
      entry = new LinkedHashMap<>();
      entry.put("name", workerName);
      entry.put("runs", 0);
      workers.add(entry);
    }

    entry.put("runs", ((Number) entry.getOrDefault("runs", 0)).intValue() + 1);
    entry.put("lastOutcome", outcome);
    entry.put("lastTimestamp", Instant.now().toString());
    episodic.engineSet("workers", workers);
  }

  /**
   * Appends a milestone name to episodic.milestones (no duplicates). Safe to call whether or not
   * the episodic panel is frozen.
   */
  public static void recordMilestoneReached(CaseContextImpl ctx, String milestoneName) {
    WritablePanelImpl episodic = ctx.writablePanel(ContextPanel.EPISODIC);
    List<String> milestones = episodic.getList("milestones", String.class);
    if (milestones == null) milestones = new ArrayList<>();
    else milestones = new ArrayList<>(milestones);
    if (!milestones.contains(milestoneName)) {
      milestones.add(milestoneName);
      episodic.engineSet("milestones", milestones);
    }
  }

  /**
   * Appends a goal name to episodic.goals (no duplicates). Safe to call whether or not the episodic
   * panel is frozen.
   */
  public static void recordGoalReached(CaseContextImpl ctx, String goalName) {
    WritablePanelImpl episodic = ctx.writablePanel(ContextPanel.EPISODIC);
    List<String> goals = episodic.getList("goals", String.class);
    if (goals == null) goals = new ArrayList<>();
    else goals = new ArrayList<>(goals);
    if (!goals.contains(goalName)) {
      goals.add(goalName);
      episodic.engineSet("goals", goals);
    }
  }
}
