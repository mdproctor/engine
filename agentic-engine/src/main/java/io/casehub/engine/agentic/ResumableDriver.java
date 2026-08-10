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
import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.ExecutionResult;
import io.casehub.blocks.agentic.model.ExecutionState;
import io.casehub.blocks.agentic.model.OrchestratedDriver;
import io.casehub.engine.plan.execution.PatternExecutionCheckpoint;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ResumableDriver<T> extends OrchestratedDriver<T> {

  private final PatternExecutionCheckpoint checkpoint;

  public ResumableDriver(
      io.casehub.blocks.agentic.model.AgentInvoker<T> invoker,
      PatternExecutionCheckpoint checkpoint) {
    super(invoker);
    this.checkpoint = checkpoint;
  }

  @Override
  protected Uni<ExecutionResult> runLoop(ExecutionModel<T> model, T context) {
    return Uni.createFrom()
        .item(
            () -> {
              var start = Instant.now();
              var allResults = restoreResults(model);
              int iteration = checkpoint.completedIterations();

              restoreDriverState(model);

              while (!isCancelled()) {
                transition(model, new ExecutionState.Running(iteration));
                var result = executeIteration(model, context, iteration, start, allResults);
                if (result != null) {
                  return result;
                }
                iteration++;
              }

              transition(model, new ExecutionState.Cancelled());
              return new ExecutionResult.Cancelled();
            });
  }

  private ArrayList<AgentResult> restoreResults(ExecutionModel<T> model) {
    var candidates = model.candidateSupplier().get();
    var nameToRef = new HashMap<String, AgentRef>();
    for (var c : candidates) {
      nameToRef.put(c.ref().name(), c.ref());
    }
    var restored = new ArrayList<AgentResult>();
    for (var record : checkpoint.results()) {
      var ref = nameToRef.getOrDefault(record.agentId(), placeholderRef(record.agentId()));
      var status = AgentResult.AgentResultStatus.valueOf(record.status());
      restored.add(
          new AgentResult(ref, record.output(), Duration.ofMillis(record.durationMs()), status));
    }
    return restored;
  }

  @SuppressWarnings("unchecked")
  private void restoreDriverState(ExecutionModel<T> model) {
    iterationCount = checkpoint.completedIterations();

    var candidates = model.candidateSupplier().get();
    var nameToRef = new HashMap<String, AgentRef>();
    for (var c : candidates) {
      nameToRef.put(c.ref().name(), c.ref());
    }

    var driverState = checkpoint.driverState();
    if (driverState.containsKey("activationCounts")) {
      var counts = (Map<String, Integer>) driverState.get("activationCounts");
      counts.forEach(
          (name, count) -> {
            var ref = nameToRef.get(name);
            if (ref != null) {
              activationCounts.put(ref, count);
            }
          });
    }
    if (driverState.containsKey("consecutiveIdleCounts")) {
      var counts = (Map<String, Integer>) driverState.get("consecutiveIdleCounts");
      counts.forEach(
          (name, count) -> {
            var ref = nameToRef.get(name);
            if (ref != null) {
              consecutiveIdleCounts.put(ref, count);
            }
          });
    }
  }

  private static AgentRef placeholderRef(String agentId) {
    return AgentRef.external(
        agentId, ctx -> java.util.concurrent.CompletableFuture.completedFuture(null));
  }
}
