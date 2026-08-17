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
package io.casehub.engine.annotations.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnnotationWorkerFunctionTest {

  public interface TestInterface {
    default TestOutput compute(String input) {
      return new TestOutput("computed: " + input);
    }

    default TestOutput multiParam(String input, TestInput data) {
      return new TestOutput(input + ":" + data.value());
    }
  }

  public static class TestImpl implements TestInterface {}

  public record TestInput(String value) {}

  public record TestOutput(String value) {}

  @Test
  void invokes_default_method() {
    var function =
        AnnotationWorkerFunction.create(
            TestImpl.class.getName(),
            "compute",
            List.of(new WorkerParamDescriptor("input", "input", "java.lang.String")),
            TestOutput.class.getName(),
            "testOutput");

    WorkerResult<?> result = function.fn().apply(Map.of("input", "hello"), null);
    assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> output = (Map<String, Object>) result.output();
    assertThat(output).containsKey("testOutput");
  }

  @Test
  void handles_multiple_params() {
    var function =
        AnnotationWorkerFunction.create(
            TestImpl.class.getName(),
            "multiParam",
            List.of(
                new WorkerParamDescriptor("input", "input", "java.lang.String"),
                new WorkerParamDescriptor("data", "data", TestInput.class.getName())),
            TestOutput.class.getName(),
            "result");

    WorkerResult<?> result =
        function.fn().apply(Map.of("input", "hi", "data", Map.of("value", "world")), null);
    assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> output = (Map<String, Object>) result.output();
    assertThat(output).containsKey("result");
  }

  @Test
  void returns_failed_on_missing_method() {
    var function =
        AnnotationWorkerFunction.create(
            TestImpl.class.getName(), "nonExistent", List.of(), "java.lang.String", "out");

    WorkerResult<?> result = function.fn().apply(Map.of(), null);
    assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
  }

  @Test
  void returns_sync_worker_function() {
    var function =
        AnnotationWorkerFunction.create(
            TestImpl.class.getName(),
            "compute",
            List.of(new WorkerParamDescriptor("input", "input", "java.lang.String")),
            TestOutput.class.getName(),
            "out");

    assertThat(function).isInstanceOf(WorkerFunction.Sync.class);
    assertThat(function.inputType()).isEqualTo(Map.class);
    assertThat(function.outputType()).isEqualTo(Map.class);
  }
}
