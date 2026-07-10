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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerFunctionTest {

  @Test
  void sync_holds_function() {
    var fn = new WorkerFunction.Sync<>(Map.class, input -> WorkerResult.of(Map.of()));
    assertThat(fn.fn()).isNotNull();
    assertThat(fn).isInstanceOf(WorkerFunction.class);
  }

  @Test
  void agentWorkerFunction_implements_workerFunction() {
    assertThat(AgentWorkerFunction.class).isAssignableTo(WorkerFunction.class);
  }
}
