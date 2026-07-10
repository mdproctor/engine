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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.api.engine.WorkerRuntime;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerFunctionsTest {

  @AfterEach
  void cleanup() {
    WorkerExecutionContext.clear();
  }

  @Test
  void sequence_executesInOrder_accumulatesResults() {
    List<String> order = new ArrayList<>();
    WorkerFunction fnA =
        new WorkerFunction.Sync<>(
            Map.class,
            input -> {
              order.add("A");
              return WorkerResult.of(Map.of("a", "fromA"));
            });
    WorkerFunction fnB =
        new WorkerFunction.Sync<>(
            Map.class,
            input -> {
              order.add("B");
              assertEquals("fromA", input.get("a"));
              return WorkerResult.of(Map.of("b", "fromB"));
            });

    var stubRuntime = new StubWorkerRuntime();
    WorkerExecutionContext.setRuntime(stubRuntime);

    @SuppressWarnings("unchecked")
    WorkerFunction.Sync<Map<String, Object>> seq =
        (WorkerFunction.Sync<Map<String, Object>>)
            (WorkerFunction.Sync<?>) WorkerFunctions.sequence(fnA, fnB);
    WorkerResult result = seq.fn().apply(Map.of("initial", "data"));

    assertEquals(List.of("A", "B"), order);
    assertEquals("fromA", result.output().get("a"));
    assertEquals("fromB", result.output().get("b"));
    assertEquals("data", result.output().get("initial"));
    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
  }

  @Test
  void sequence_failFast_stopsOnNonSuccess() {
    List<String> order = new ArrayList<>();
    WorkerFunction fnA =
        new WorkerFunction.Sync<>(
            Map.class,
            input -> {
              order.add("A");
              return WorkerResult.declined("not ready");
            });
    WorkerFunction fnB =
        new WorkerFunction.Sync<>(
            Map.class,
            input -> {
              order.add("B");
              return WorkerResult.of(Map.of());
            });

    WorkerExecutionContext.setRuntime(new StubWorkerRuntime());
    WorkerResult result =
        ((java.util.function.Function<Map<String, Object>, WorkerResult>)
                WorkerFunctions.sequence(fnA, fnB).fn())
            .apply(Map.of());

    assertEquals(List.of("A"), order);
    assertInstanceOf(WorkerOutcome.Declined.class, result.outcome());
  }

  @Test
  void sequence_noRuntime_returnsFailed() {
    WorkerFunction fn = new WorkerFunction.Sync<>(Map.class, input -> WorkerResult.of(Map.of()));
    WorkerResult result =
        ((java.util.function.Function<Map<String, Object>, WorkerResult>)
                WorkerFunctions.sequence(fn).fn())
            .apply(Map.of());
    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
  }

  @Test
  void sequence_emptySteps_throws() {
    assertThrows(IllegalArgumentException.class, () -> WorkerFunctions.sequence());
  }

  @Test
  void merge_overlayWins() {
    var base = Map.<String, Object>of("a", 1, "b", 2);
    var overlay = Map.<String, Object>of("b", 99, "c", 3);
    var result = WorkerFunctions.merge(base, overlay);
    assertEquals(1, result.get("a"));
    assertEquals(99, result.get("b"));
    assertEquals(3, result.get("c"));
  }

  /** Minimal stub that delegates execute() directly to the function. */
  private static class StubWorkerRuntime implements WorkerRuntime {
    @Override
    public java.util.UUID caseId() {
      return java.util.UUID.randomUUID();
    }

    @Override
    public WorkerResult execute(WorkerFunction function, Map<String, Object> input) {
      if (function instanceof WorkerFunction.Sync<?> sync) {
        try {
          @SuppressWarnings("unchecked")
          var fn = (java.util.function.Function<Map<String, Object>, WorkerResult>) sync.fn();
          return fn.apply(input);
        } catch (Exception e) {
          return WorkerResult.failed(e.getMessage());
        }
      }
      return WorkerResult.failed("unsupported function type");
    }

    @Override
    public WorkerResult execute(String n, Map<String, Object> i) {
      return WorkerResult.failed("stub");
    }

    @Override
    public java.util.UUID spawnCase(String t, Map<String, Object> i) {
      return null;
    }

    @Override
    public io.casehub.api.context.CaseContext awaitCase(java.util.UUID id, java.time.Duration t) {
      return null;
    }

    @Override
    public io.casehub.api.context.CaseContext spawnAndAwaitCase(
        String t, Map<String, Object> i, java.time.Duration d) {
      return null;
    }
  }
}
