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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatternExecutionCheckpointTest {

  @Test
  void constructionPreservesFields() {
    var caseId = UUID.randomUUID();
    var results = List.of(AgentResultRecord.of("a1", Map.of("k", "v"), 100L, "SUCCESS"));
    var driverState =
        Map.<String, Object>of(
            "activationCounts", Map.of("a1", 1),
            "consecutiveIdleCounts", Map.of("a1", 0));

    var checkpoint =
        new PatternExecutionCheckpoint(
            caseId, "pattern-worker", 3, results, Set.of("excluded-agent"), null, 0, driverState);

    assertThat(checkpoint.caseId()).isEqualTo(caseId);
    assertThat(checkpoint.patternId()).isEqualTo("pattern-worker");
    assertThat(checkpoint.completedIterations()).isEqualTo(3);
    assertThat(checkpoint.results()).hasSize(1);
    assertThat(checkpoint.excludedAgents()).containsExactly("excluded-agent");
    assertThat(checkpoint.currentPlan()).isNull();
    assertThat(checkpoint.replanCount()).isZero();
    assertThat(checkpoint.driverState()).containsKey("activationCounts");
  }

  @Test
  void resultsListIsDefensivelyCopied() {
    var mutableResults = new ArrayList<>(List.of(AgentResultRecord.of("a1", null, 0L, "SUCCESS")));
    var checkpoint =
        new PatternExecutionCheckpoint(
            UUID.randomUUID(), "p", 1, mutableResults, Set.of(), null, 0, Map.of());
    mutableResults.clear();
    assertThat(checkpoint.results()).hasSize(1);
  }

  @Test
  void excludedAgentsSetIsDefensivelyCopied() {
    var mutableSet = new HashSet<>(Set.of("a1"));
    var checkpoint =
        new PatternExecutionCheckpoint(
            UUID.randomUUID(), "p", 1, List.of(), mutableSet, null, 0, Map.of());
    mutableSet.clear();
    assertThat(checkpoint.excludedAgents()).hasSize(1);
  }
}
