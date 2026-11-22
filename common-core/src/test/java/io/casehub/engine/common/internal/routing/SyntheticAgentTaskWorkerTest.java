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
package io.casehub.engine.common.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.AgentTaskTarget;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.ai.DeferredAgentConfig;
import io.casehub.worker.api.Worker;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SyntheticAgentTaskWorkerTest {

  @Test
  void create_setsCorrectName() {
    var target = AgentTaskTarget.builder().capability("risk-assessment").build();
    Worker worker = SyntheticAgentTaskWorker.create("check-risk", target);
    assertThat(worker.name()).isEqualTo("__agent-task__check-risk");
  }

  @Test
  void create_setsCapabilityConfig() {
    var target =
        AgentTaskTarget.builder()
            .capability("risk-assessment")
            .domain("aml")
            .model("claude-sonnet-5-5")
            .inputMapping(".ticket")
            .build();
    Worker worker = SyntheticAgentTaskWorker.create("check-risk", target);

    var fn = (AgentWorkerFunction) worker.function();
    assertThat(fn.isDeferred()).isTrue();
    var config = (DeferredAgentConfig.CapabilityConfig) fn.deferredConfig();
    assertThat(config.capability()).isEqualTo("risk-assessment");
    assertThat(config.domain()).isEqualTo("aml");
    assertThat(config.model()).isEqualTo("claude-sonnet-5-5");
    assertThat(config.inputEvaluator()).isNotNull();
    assertThat(config.outputEvaluator()).isNull();
  }

  @Test
  void create_withoutOptionalFields() {
    var target = AgentTaskTarget.builder().capability("risk-assessment").build();
    Worker worker = SyntheticAgentTaskWorker.create("check-risk", target);

    var config =
        (DeferredAgentConfig.CapabilityConfig)
            ((AgentWorkerFunction) worker.function()).deferredConfig();
    assertThat(config.capability()).isEqualTo("risk-assessment");
    assertThat(config.domain()).isNull();
    assertThat(config.model()).isNull();
    assertThat(config.inputEvaluator()).isNull();
    assertThat(config.outputEvaluator()).isNull();
  }

  @Test
  void validateNoCollision_passesForSyntheticOnly() {
    var syntheticNames = Set.of("__agent-task__check-risk");
    var workers =
        List.of(
            Worker.builder().name("my-worker").capabilityName("x").noFunction().build(),
            Worker.builder()
                .name("__agent-task__check-risk")
                .capabilityName("c")
                .function(
                    AgentWorkerFunction.ofCapability(
                        new DeferredAgentConfig.CapabilityConfig("c", null, null, null, null)))
                .build());

    SyntheticAgentTaskWorker.validateNoCollision(workers, syntheticNames);
  }

  @Test
  void validateNoCollision_throwsForUserWorkerWithReservedPrefix() {
    var syntheticNames = Set.of("__agent-task__check-risk");
    var workers =
        List.of(
            Worker.builder()
                .name("__agent-task__user-worker")
                .capabilityName("x")
                .noFunction()
                .build());

    assertThatThrownBy(() -> SyntheticAgentTaskWorker.validateNoCollision(workers, syntheticNames))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("__agent-task__");
  }
}
