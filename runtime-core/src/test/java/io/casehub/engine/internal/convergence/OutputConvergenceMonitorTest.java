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
package io.casehub.engine.internal.convergence;

import static org.assertj.core.api.Assertions.*;

import io.casehub.api.model.convergence.OutputConvergenceConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutputConvergenceMonitorTest {

  private final OutputConvergenceMonitor monitor = new OutputConvergenceMonitor();

  @Test
  void no_detection_below_min_samples() {
    var caseId = UUID.randomUUID();
    var config = OutputConvergenceConfig.defaults();
    monitor.recordOutput(caseId, "binding1", "agent1", Map.of("key", "value1"));
    monitor.recordOutput(caseId, "binding1", "agent2", Map.of("key", "value2"));
    assertThat(monitor.detectConvergence(caseId, "binding1", config)).isEmpty();
  }

  @Test
  void detects_identical_outputs() {
    var caseId = UUID.randomUUID();
    var config = new OutputConvergenceConfig(0.9, 3, 10);
    var output = Map.<String, Object>of("result", "same", "score", 42);
    monitor.recordOutput(caseId, "binding1", "agent1", output);
    monitor.recordOutput(caseId, "binding1", "agent2", output);
    monitor.recordOutput(caseId, "binding1", "agent3", output);
    var result = monitor.detectConvergence(caseId, "binding1", config);
    assertThat(result).isPresent();
    assertThat(result.get().averageJaccard()).isEqualTo(1.0);
  }

  @Test
  void no_detection_for_diverse_outputs() {
    var caseId = UUID.randomUUID();
    var config = new OutputConvergenceConfig(0.9, 3, 10);
    monitor.recordOutput(caseId, "binding1", "agent1", Map.of("a", 1));
    monitor.recordOutput(caseId, "binding1", "agent2", Map.of("b", 2));
    monitor.recordOutput(caseId, "binding1", "agent3", Map.of("c", 3));
    assertThat(monitor.detectConvergence(caseId, "binding1", config)).isEmpty();
  }

  @Test
  void evict_clears_state() {
    var caseId = UUID.randomUUID();
    monitor.recordOutput(caseId, "binding1", "agent1", Map.of("key", "val"));
    monitor.evictByCase(caseId);
    var config = new OutputConvergenceConfig(0.9, 2, 10);
    monitor.recordOutput(caseId, "binding1", "agent2", Map.of("key", "val"));
    assertThat(monitor.detectConvergence(caseId, "binding1", config)).isEmpty();
  }

  @Test
  void window_evicts_old_entries() {
    var caseId = UUID.randomUUID();
    var config = new OutputConvergenceConfig(0.9, 3, 3);
    monitor.recordOutput(caseId, "b", "a1", Map.of("old", 1));
    monitor.recordOutput(caseId, "b", "a2", Map.of("same", 1));
    monitor.recordOutput(caseId, "b", "a3", Map.of("same", 1));
    monitor.recordOutput(caseId, "b", "a4", Map.of("same", 1));
    var result = monitor.detectConvergence(caseId, "b", config);
    assertThat(result).isPresent();
  }

  @Test
  void cross_binding_isolation() {
    var caseId = UUID.randomUUID();
    var config = new OutputConvergenceConfig(0.9, 3, 10);
    var output = Map.<String, Object>of("key", "same");
    monitor.recordOutput(caseId, "binding1", "a1", output);
    monitor.recordOutput(caseId, "binding1", "a2", output);
    monitor.recordOutput(caseId, "binding2", "a3", output);
    assertThat(monitor.detectConvergence(caseId, "binding1", config)).isEmpty();
  }

  @Test
  void cross_case_isolation() {
    var case1 = UUID.randomUUID();
    var case2 = UUID.randomUUID();
    var config = new OutputConvergenceConfig(0.9, 3, 10);
    var output = Map.<String, Object>of("key", "same");
    monitor.recordOutput(case1, "b", "a1", output);
    monitor.recordOutput(case1, "b", "a2", output);
    monitor.recordOutput(case2, "b", "a3", output);
    assertThat(monitor.detectConvergence(case1, "b", config)).isEmpty();
  }

  @Test
  void null_config_returns_empty() {
    var caseId = UUID.randomUUID();
    monitor.recordOutput(caseId, "b", "a1", Map.of("k", "v"));
    assertThat(monitor.detectConvergence(caseId, "b", null)).isEmpty();
  }

  @Test
  void affected_agents_tracked() {
    var caseId = UUID.randomUUID();
    var config = new OutputConvergenceConfig(0.9, 3, 10);
    var output = Map.<String, Object>of("key", "same");
    monitor.recordOutput(caseId, "b", "agent-A", output);
    monitor.recordOutput(caseId, "b", "agent-B", output);
    monitor.recordOutput(caseId, "b", "agent-C", output);
    var result = monitor.detectConvergence(caseId, "b", config);
    assertThat(result).isPresent();
    assertThat(result.get().affectedAgents())
        .containsExactlyInAnyOrder("agent-A", "agent-B", "agent-C");
  }

  @Test
  void reset_clears_all_state() {
    var caseId = UUID.randomUUID();
    monitor.recordOutput(caseId, "b", "a1", Map.of("k", "v"));
    monitor.reset();
    var config = new OutputConvergenceConfig(0.9, 2, 10);
    monitor.recordOutput(caseId, "b", "a2", Map.of("k", "v"));
    assertThat(monitor.detectConvergence(caseId, "b", config)).isEmpty();
  }
}
