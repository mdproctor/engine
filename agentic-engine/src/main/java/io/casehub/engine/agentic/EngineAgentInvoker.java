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

import io.casehub.api.engine.WorkerRuntime;
import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.model.AgentInvoker;
import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.ExecutionResult;
import io.casehub.blocks.agentic.model.OrchestratedDriver;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class EngineAgentInvoker<T> implements AgentInvoker<T> {

  private final WorkerRuntime runtime;

  public EngineAgentInvoker(WorkerRuntime runtime) {
    this.runtime = runtime;
  }

  @Override
  public Uni<AgentResult> invoke(AgentRef agent, T state) {
    return Uni.createFrom()
        .item(
            () -> {
              var start = Instant.now();
              return switch (agent) {
                case AgentRef.ExternalAgent ext -> invokeExternal(ext, state, start);
                case AgentRef.WorkerAgent wa -> invokeWorker(wa, state, start);
                case AgentRef.ComposedAgent ca -> invokeComposed(ca, state, start);
                case AgentRef.ChannelAgent ignored ->
                    AgentResult.failure(
                        agent,
                        "ChannelAgent not supported in v1 — requires Qhorus SPI on WorkerRuntime");
                case AgentRef.HumanAgent ignored ->
                    AgentResult.failure(
                        agent,
                        "HumanAgent not supported in v1 — requires WorkItem SPI on WorkerRuntime");
              };
            });
  }

  private AgentResult invokeExternal(AgentRef.ExternalAgent ext, T state, Instant start) {
    try {
      var result = ext.fn().apply(state).toCompletableFuture().join();
      var elapsed = Duration.between(start, Instant.now());
      return new AgentResult(ext, result.output(), elapsed, result.status());
    } catch (Exception e) {
      return AgentResult.failure(ext, "ExternalAgent failed: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private AgentResult invokeWorker(AgentRef.WorkerAgent wa, T state, Instant start) {
    try {
      Map<String, Object> input =
          state instanceof Map ? (Map<String, Object>) state : Map.of("context", state);
      WorkerResult<?> result = runtime.execute(wa.worker().name(), input);
      var elapsed = Duration.between(start, Instant.now());
      if (result.outcome() instanceof io.casehub.worker.api.WorkerOutcome.Success) {
        return new AgentResult(wa, result.output(), elapsed, AgentResult.AgentResultStatus.SUCCESS);
      }
      return new AgentResult(
          wa, result.outcome().toString(), elapsed, AgentResult.AgentResultStatus.FAILURE);
    } catch (Exception e) {
      return AgentResult.failure(wa, "WorkerAgent dispatch failed: " + e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private AgentResult invokeComposed(AgentRef.ComposedAgent ca, T state, Instant start) {
    try {
      var model = (ExecutionModel<Object>) (ExecutionModel<?>) ca.model();
      var nested = new EngineAgentInvoker<>(runtime);
      var driver = new OrchestratedDriver<>(nested);
      var executionResult = driver.execute(model, state).await().indefinitely();
      var elapsed = Duration.between(start, Instant.now());
      return switch (executionResult) {
        case ExecutionResult.Completed c ->
            new AgentResult(ca, c.result(), elapsed, AgentResult.AgentResultStatus.SUCCESS);
        case ExecutionResult.Failed f ->
            new AgentResult(ca, f.reason(), elapsed, AgentResult.AgentResultStatus.FAILURE);
        case ExecutionResult.Escalated e ->
            new AgentResult(
                ca, "Escalated: " + e.reason(), elapsed, AgentResult.AgentResultStatus.FAILURE);
        case ExecutionResult.Cancelled ignored ->
            new AgentResult(ca, "Cancelled", elapsed, AgentResult.AgentResultStatus.FAILURE);
      };
    } catch (Exception e) {
      return AgentResult.failure(ca, "ComposedAgent failed: " + e.getMessage());
    }
  }
}
