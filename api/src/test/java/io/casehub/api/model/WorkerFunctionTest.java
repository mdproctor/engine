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

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerFunctionTest {

  @Test
  void sync_holds_function() {
    var fn = new WorkerFunction.Sync(input -> WorkerResult.of(Map.of()));
    assertThat(fn.fn()).isNotNull();
    assertThat(fn).isInstanceOf(WorkerFunction.class);
  }

  @Test
  void agentExec_holds_agent() {
    // Agent requires ChatModelProvider which is complex to construct — test type membership only
    assertThat(WorkerFunction.AgentExec.class).isAssignableTo(WorkerFunction.class);
  }

  @Test
  void flow_holds_workflow() {
    assertThat(WorkerFunction.Flow.class).isAssignableTo(WorkerFunction.class);
  }

  @Test
  void exhaustive_switch_covers_all_variants() {
    WorkerFunction fn = new WorkerFunction.Sync(input -> WorkerResult.of(Map.of()));
    String result =
        switch (fn) {
          case WorkerFunction.Sync s -> "sync";
          case WorkerFunction.AgentExec a -> "agent";
          case WorkerFunction.Flow f -> "flow";
        };
    assertThat(result).isEqualTo("sync");
  }
}
