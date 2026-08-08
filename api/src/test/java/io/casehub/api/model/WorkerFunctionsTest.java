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
  void cleanup() {}

  @Test
  void sequence_executesInOrder_accumulatesResults() {
    List<String> order = new ArrayList<>();
    WorkerFunction<?, ?> fnA =
        new WorkerFunction.Sync<>(
            Map.class,
            Map.class,
            (input, scope) -> {
              order.add("A");
              return WorkerResult.of(Map.of("a", "fromA"));
            });
    WorkerFunction<?, ?> fnB =
        new WorkerFunction.Sync<>(
            Map.class,
            Map.class,
            (input, scope) -> {
              order.add("B");
              assertEquals("fromA", ((Map<?, ?>) input).get("a"));
              return WorkerResult.of(Map.of("b", "fromB"));
            });

    var stubRuntime = new StubWorkerRuntime();

    var seq = WorkerFunctions.sequence(fnA, fnB);
    WorkerResult<?> result = seq.fn().apply(Map.of("initial", "data"), stubRuntime);

    assertEquals(List.of("A", "B"), order);
    @SuppressWarnings("unchecked")
    Map<String, Object> output = (Map<String, Object>) result.output();
    assertEquals("fromA", output.get("a"));
    assertEquals("fromB", output.get("b"));
    assertEquals("data", output.get("initial"));
    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
  }

  @Test
  void sequence_failFast_stopsOnNonSuccess() {
    List<String> order = new ArrayList<>();
    WorkerFunction<?, ?> fnA =
        new WorkerFunction.Sync<>(
            Map.class,
            Map.class,
            (input, scope) -> {
              order.add("A");
              return WorkerResult.declined("not ready");
            });
    WorkerFunction<?, ?> fnB =
        new WorkerFunction.Sync<>(
            Map.class,
            Map.class,
            (input, scope) -> {
              order.add("B");
              return WorkerResult.of(Map.of());
            });

    var stubRuntime = new StubWorkerRuntime();
    WorkerResult<?> result = WorkerFunctions.sequence(fnA, fnB).fn().apply(Map.of(), stubRuntime);

    assertEquals(List.of("A"), order);
    assertInstanceOf(WorkerOutcome.Declined.class, result.outcome());
  }

  @Test
  void sequence_noRuntime_returnsFailed() {
    WorkerFunction<?, ?> fn =
        new WorkerFunction.Sync<>(
            Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of()));
    WorkerResult<?> result = WorkerFunctions.sequence(fn).fn().apply(Map.of(), null);
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

  @Test
  void exchangeSequence_chainsMultipleSteps() {
    var step1 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class,
            String.class,
            (ex, scope) -> WorkerResult.of(ex.withBody(ex.body().toUpperCase())));
    var step2 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class,
            String.class,
            (ex, scope) -> WorkerResult.of(ex.withBody(ex.body() + "!")));
    var seq = WorkerFunctions.exchangeSequence(step1, step2);
    var result = seq.fn().apply(io.casehub.worker.api.Exchange.of("hello"), null);
    assertEquals("HELLO!", result.output().body());
  }

  @Test
  void exchangeSequence_emptyThrows() {
    assertThrows(IllegalArgumentException.class, () -> WorkerFunctions.exchangeSequence());
  }

  @Test
  void exchangeSequence_singleStep() {
    var step =
        new WorkerFunction.ExchangeProcessor<>(
            String.class, String.class, (ex, scope) -> WorkerResult.of(ex.withBody("done")));
    var seq = WorkerFunctions.exchangeSequence(step);
    var result = seq.fn().apply(io.casehub.worker.api.Exchange.of("start"), null);
    assertEquals("done", result.output().body());
  }

  @Test
  void exchangeSequence_shortCircuitsOnDeclined() {
    var step1 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class, String.class, (ex, scope) -> WorkerResult.declined("no"));
    var step2 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class,
            String.class,
            (ex, scope) -> {
              throw new AssertionError("should not be called");
            });
    var seq = WorkerFunctions.exchangeSequence(step1, step2);
    var result = seq.fn().apply(io.casehub.worker.api.Exchange.of("hello"), null);
    assertInstanceOf(WorkerOutcome.Declined.class, result.outcome());
  }

  @Test
  void exchangeSequence_headersAccumulate() {
    var step1 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class, String.class, (ex, scope) -> WorkerResult.of(ex.withHeader("h1", "v1")));
    var step2 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class, String.class, (ex, scope) -> WorkerResult.of(ex.withHeader("h2", "v2")));
    var seq = WorkerFunctions.exchangeSequence(step1, step2);
    var result = seq.fn().apply(io.casehub.worker.api.Exchange.of("body"), null);
    assertEquals("v1", result.output().headers().get("h1"));
    assertEquals("v2", result.output().headers().get("h2"));
  }

  @Test
  void exchangeSequence_propertiesAccumulate() {
    var step1 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class,
            String.class,
            (ex, scope) -> WorkerResult.of(ex.withProperty("p1", "v1")));
    var step2 =
        new WorkerFunction.ExchangeProcessor<>(
            String.class,
            String.class,
            (ex, scope) -> WorkerResult.of(ex.withProperty("p2", "v2")));
    var seq = WorkerFunctions.exchangeSequence(step1, step2);
    var result = seq.fn().apply(io.casehub.worker.api.Exchange.of("body"), null);
    assertEquals("v1", result.output().properties().get("p1"));
    assertEquals("v2", result.output().properties().get("p2"));
  }

  @Test
  void exchangeSequence_constructionTimeTypeValidation() {
    var strToStr =
        new WorkerFunction.ExchangeProcessor<>(
            String.class, String.class, (ex, scope) -> WorkerResult.of(ex));
    var intToInt =
        new WorkerFunction.ExchangeProcessor<>(
            Integer.class, Integer.class, (ex, scope) -> WorkerResult.of(ex));
    assertThrows(
        IllegalArgumentException.class, () -> WorkerFunctions.exchangeSequence(strToStr, intToInt));
  }

  /** Minimal stub that delegates execute() directly to the function. */
  @SuppressWarnings("unchecked")
  private static class StubWorkerRuntime implements WorkerRuntime {
    @Override
    public java.util.UUID caseId() {
      return java.util.UUID.randomUUID();
    }

    @Override
    public String taskId() {
      return "stub-task";
    }

    @Override
    public io.casehub.api.model.WorkerContext context() {
      return null;
    }

    @Override
    public <T, R> WorkerResult<R> execute(WorkerFunction<T, R> function, T input) {
      if (function instanceof WorkerFunction.Sync<T, R> sync) {
        try {
          return sync.fn().apply(input, this);
        } catch (Exception e) {
          return (WorkerResult<R>) WorkerResult.failed(e.getMessage());
        }
      }
      return (WorkerResult<R>) WorkerResult.failed("unsupported function type");
    }

    @Override
    public WorkerResult<?> execute(String n, Map<String, Object> i) {
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
