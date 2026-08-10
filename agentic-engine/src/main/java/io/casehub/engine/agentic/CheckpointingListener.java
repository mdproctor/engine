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
package io.casehub.engine.agentic;

import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.model.ExecutionEventListener;
import io.casehub.blocks.agentic.termination.TerminationDecision;
import io.casehub.engine.plan.execution.AgentResultRecord;
import io.casehub.engine.plan.execution.PatternExecutionCheckpoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

public class CheckpointingListener implements ExecutionEventListener {

  private final UUID caseId;
  private final String patternId;
  private final String tenancyId;
  private final BiConsumer<PatternExecutionCheckpoint, String> persistFn;

  private final List<AgentResultRecord> allResults = new ArrayList<>();
  private final Map<String, Integer> activationCounts = new HashMap<>();
  private final Map<String, Integer> consecutiveIdleCounts = new HashMap<>();
  private int completedIterations = 0;

  public CheckpointingListener(
      UUID caseId,
      String patternId,
      String tenancyId,
      BiConsumer<PatternExecutionCheckpoint, String> persistFn) {
    this.caseId = caseId;
    this.patternId = patternId;
    this.tenancyId = tenancyId;
    this.persistFn = persistFn;
  }

  @Override
  public void onActivation(AgentRef agent, boolean activated) {
    String name = agent.name();
    if (activated) {
      activationCounts.merge(name, 1, Integer::sum);
      consecutiveIdleCounts.put(name, 0);
    } else {
      consecutiveIdleCounts.merge(name, 1, Integer::sum);
    }
  }

  @Override
  public void onAgentResult(AgentResult result) {
    allResults.add(
        AgentResultRecord.of(
            result.agent().name(),
            result.output(),
            result.duration().toMillis(),
            result.status().name()));
  }

  @Override
  public void onTermination(TerminationDecision decision) {
    if (decision instanceof TerminationDecision.Continue) {
      completedIterations++;
      writeCheckpoint();
    }
  }

  private void writeCheckpoint() {
    var driverState =
        Map.<String, Object>of(
            "activationCounts", Map.copyOf(activationCounts),
            "consecutiveIdleCounts", Map.copyOf(consecutiveIdleCounts));
    var checkpoint =
        new PatternExecutionCheckpoint(
            caseId,
            patternId,
            completedIterations,
            List.copyOf(allResults),
            Set.of(),
            null,
            0,
            driverState);
    persistFn.accept(checkpoint, tenancyId);
  }
}
