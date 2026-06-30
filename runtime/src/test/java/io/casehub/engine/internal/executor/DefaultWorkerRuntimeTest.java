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
package io.casehub.engine.internal.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeTest {

  private static final UUID CASE_ID = UUID.randomUUID();
  private DefaultWorkerRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new DefaultWorkerRuntime(CASE_ID, null, null, null);
  }

  @AfterEach
  void cleanup() {
    WorkerExecutionContext.clear();
  }

  @Test
  void caseId_returnsConstructorValue() {
    assertEquals(CASE_ID, runtime.caseId());
  }

  @Test
  void execute_syncFunction_returnsResult() {
    WorkerFunction fn =
        new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("result", "hello")));

    WorkerResult result = runtime.execute(fn, Map.of("key", "value"));

    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
    assertEquals("hello", result.output().get("result"));
  }

  @Test
  void execute_throwingFunction_wrapsInFailed() {
    WorkerFunction fn =
        new WorkerFunction.Sync(
            input -> {
              throw new RuntimeException("boom");
            });

    WorkerResult result = runtime.execute(fn, Map.of());

    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
    assertEquals("boom", ((WorkerOutcome.Failed) result.outcome()).reason());
  }

  @Test
  void execute_preservesParentContext() {
    var parentContext =
        new io.casehub.api.model.WorkerContext("parent-task", CASE_ID, null, null, null, null);
    WorkerExecutionContext.set(parentContext);

    WorkerFunction fn =
        new WorkerFunction.Sync(
            input -> {
              var innerCtx = WorkerExecutionContext.current();
              assertNotNull(innerCtx);
              assertEquals(CASE_ID, innerCtx.caseId());
              return WorkerResult.of(Map.of());
            });

    runtime.execute(fn, Map.of());

    assertSame(parentContext, WorkerExecutionContext.current());
  }

  @Test
  void execute_nestedOrchestration_stackSemantics() {
    List<String> order = new ArrayList<>();
    WorkerFunction inner =
        new WorkerFunction.Sync(
            input -> {
              order.add("inner");
              return WorkerResult.of(Map.of("inner", true));
            });
    WorkerFunction outer =
        new WorkerFunction.Sync(
            input -> {
              order.add("outer-start");
              var rt = WorkerExecutionContext.currentRuntime();
              var result = rt.execute(inner, input);
              order.add("outer-end");
              return result;
            });

    WorkerExecutionContext.setRuntime(runtime);
    WorkerResult result = runtime.execute(outer, Map.of());

    assertEquals(List.of("outer-start", "inner", "outer-end"), order);
    assertTrue((Boolean) result.output().get("inner"));
  }

  @Test
  void execute_unsupportedFunctionType_returnsFailed() {
    WorkerResult result = runtime.execute(WorkerFunction.NONE, Map.of());
    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
  }
}
