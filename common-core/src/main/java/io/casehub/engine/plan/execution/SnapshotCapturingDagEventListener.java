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
package io.casehub.engine.plan.execution;

import io.casehub.engine.plan.DagEventListener;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DagResult;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SnapshotCapturingDagEventListener<T, R> implements DagEventListener<T, R> {

  private final UUID caseId;
  private final String tenancyId;
  private final ExecutionSnapshotStore store;
  private final Map<String, Instant> dispatchTimes = new ConcurrentHashMap<>();
  private final Map<String, Long> nodeDurations = new ConcurrentHashMap<>();
  private final java.util.Map<String, DagResultSnapshot> contingencyResults =
      new java.util.concurrent.ConcurrentHashMap<>();

  public SnapshotCapturingDagEventListener(
      UUID caseId, String tenancyId, ExecutionSnapshotStore store, DagPlan<T> plan) {
    this.caseId = caseId;
    this.tenancyId = tenancyId;
    this.store = store;
    store.storeDagPlan(caseId, tenancyId, DagPlanSnapshot.from(plan, Instant.now()));
  }

  @Override
  public void onNodeDispatched(String nodeId, T task) {
    dispatchTimes.put(nodeId, Instant.now());
  }

  @Override
  public void onNodeCompleted(String nodeId, T task, R result) {
    recordDuration(nodeId);
  }

  @Override
  public void onNodeFailed(String nodeId, T task, String reason, Throwable cause) {
    recordDuration(nodeId);
  }

  @Override
  public void onContingencyActivated(String nodeId, T task, DagResult<R> contingencyResult) {
    recordDuration(nodeId);
    contingencyResults.put(
        nodeId, DagResultSnapshot.from(contingencyResult, java.time.Instant.now(), null));
  }

  @Override
  public void onExecutionComplete(DagResult<R> result) {
    Map<String, Long> durations = nodeDurations.isEmpty() ? null : Map.copyOf(nodeDurations);
    Map<String, DagResultSnapshot> contingencies =
        contingencyResults.isEmpty() ? null : Map.copyOf(contingencyResults);
    store.storeDagResult(
        caseId, tenancyId, DagResultSnapshot.from(result, Instant.now(), durations, contingencies));
  }

  private void recordDuration(String nodeId) {
    Instant start = dispatchTimes.get(nodeId);
    if (start != null) {
      nodeDurations.put(nodeId, Duration.between(start, Instant.now()).toMillis());
    }
  }
}
