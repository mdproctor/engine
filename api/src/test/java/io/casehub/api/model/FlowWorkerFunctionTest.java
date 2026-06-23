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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.worker.api.WorkerFunction;
import io.serverlessworkflow.api.types.Workflow;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowWorkerFunctionTest {

  @Test
  void implementsWorkerFunction() {
    Workflow workflow = new Workflow();
    var fn = new FlowWorkerFunction(workflow);
    assertInstanceOf(WorkerFunction.class, fn);
    assertSame(workflow, fn.workflow());
  }

  @Test
  void executeThrowsUnsupported() {
    var fn = new FlowWorkerFunction(new Workflow());
    assertThrows(UnsupportedOperationException.class, () -> fn.execute(Map.of()));
  }

  @Test
  void rejectsNullWorkflow() {
    assertThrows(NullPointerException.class, () -> new FlowWorkerFunction(null));
  }
}
