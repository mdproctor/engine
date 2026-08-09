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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.engine.WorkerRuntime;
import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.worker.api.WorkerResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineAgentInvokerTest {

  private WorkerRuntime runtime;
  private EngineAgentInvoker<Map<String, Object>> invoker;

  @BeforeEach
  void setUp() {
    runtime = mock(WorkerRuntime.class);
    invoker = new EngineAgentInvoker<>(runtime);
  }

  @Test
  void externalAgentCallsFunctionDirectly() {
    var agent =
        AgentRef.external(
            "test-agent",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("result", "done"))));

    var result = invoker.invoke(agent, Map.of()).await().indefinitely();
    assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.SUCCESS);
    assertThat(result.output()).isEqualTo(Map.of("result", "done"));
  }

  @Test
  void workerAgentDelegatesToRuntime() {
    when(runtime.execute(eq("analyst"), anyMap()))
        .thenReturn((WorkerResult) WorkerResult.of(Map.of("analysis", "complete")));

    var worker =
        io.casehub.worker.api.Worker.builder()
            .name("analyst")
            .capabilityName("analysis")
            .noFunction()
            .build();
    var agent = AgentRef.worker(worker);

    var result = invoker.invoke(agent, Map.of("input", "data")).await().indefinitely();
    assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.SUCCESS);
  }

  @Test
  void workerAgentFailureProducesFailureResult() {
    when(runtime.execute(eq("analyst"), anyMap())).thenReturn(WorkerResult.failed("agent error"));

    var worker =
        io.casehub.worker.api.Worker.builder()
            .name("analyst")
            .capabilityName("analysis")
            .noFunction()
            .build();
    var agent = AgentRef.worker(worker);

    var result = invoker.invoke(agent, Map.of()).await().indefinitely();
    assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.FAILURE);
  }

  @Test
  void channelAgentReturnsFailure() {
    var agent =
        AgentRef.channel(
            UUID.randomUUID(), mock(io.casehub.blocks.channel.ChannelAgentHandler.class));

    var result = invoker.invoke(agent, Map.of()).await().indefinitely();
    assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.FAILURE);
    assertThat(result.output().toString()).contains("ChannelAgent");
  }

  @Test
  void humanAgentReturnsFailure() {
    var agent = AgentRef.human(null);

    var result = invoker.invoke(agent, Map.of()).await().indefinitely();
    assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.FAILURE);
    assertThat(result.output().toString()).contains("HumanAgent");
  }
}
