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
package io.casehub.engine.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.worker.api.Capability;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReActWorkerFunctionTest {

  @Test
  void recordValidation() {
    var cap = new Capability("search", ".", ".", "Search");
    var tool = new ToolSource.WorkerTool(cap, "searcher");
    var fn = new ReActWorkerFunction(null, "You are an analyst", List.of(tool), 10);

    assertThat(fn.systemPrompt()).isEqualTo("You are an analyst");
    assertThat(fn.tools()).hasSize(1);
    assertThat(fn.maxCycles()).isEqualTo(10);
    assertThat(fn.inputType()).isEqualTo(Map.class);
    assertThat(fn.outputType()).isEqualTo(Map.class);
  }

  @Test
  void defaultMaxCyclesIs20() {
    var cap = new Capability("search", ".", ".", "Search");
    var tool = new ToolSource.WorkerTool(cap, "searcher");
    var fn = new ReActWorkerFunction(null, "prompt", List.of(tool));

    assertThat(fn.maxCycles()).isEqualTo(20);
  }

  @Test
  void rejectsEmptyToolList() {
    assertThatThrownBy(() -> new ReActWorkerFunction(null, "prompt", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one tool");
  }

  @Test
  void rejectsZeroMaxCycles() {
    var cap = new Capability("s", ".", ".", "d");
    var tool = new ToolSource.WorkerTool(cap, "w");
    assertThatThrownBy(() -> new ReActWorkerFunction(null, "p", List.of(tool), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxCycles");
  }

  @Test
  void rejectsNullToolList() {
    assertThatThrownBy(() -> new ReActWorkerFunction(null, "p", null))
        .isInstanceOf(NullPointerException.class);
  }
}
