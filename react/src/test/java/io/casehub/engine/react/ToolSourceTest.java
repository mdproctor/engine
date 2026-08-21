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

import io.casehub.worker.api.Capability;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSourceTest {

  @Test
  void workerToolDelegatesNameAndDescriptionToCapability() {
    var cap = new Capability("web-search", ".", ".", "Search the web");
    var tool = new ToolSource.WorkerTool(cap, "search-worker");

    assertThat(tool.name()).isEqualTo("web-search");
    assertThat(tool.description()).isEqualTo("Search the web");
    assertThat(tool.workerName()).isEqualTo("search-worker");
    assertThat(tool.capability()).isSameAs(cap);
  }

  @Test
  void localToolCarriesNameDescriptionFnAndSchema() {
    var tool =
        new ToolSource.LocalTool(
            "calculate",
            "Run a calculation",
            args -> Map.of("result", 42),
            Map.of("expression", Map.of("type", "string")));

    assertThat(tool.name()).isEqualTo("calculate");
    assertThat(tool.description()).isEqualTo("Run a calculation");
    assertThat(tool.fn().apply(Map.of())).containsEntry("result", 42);
    assertThat(tool.parameterSchema()).containsKey("expression");
  }

  @Test
  void sealedTypeIsExhaustive() {
    var cap = new Capability("test", ".", ".", "test");
    ToolSource source = new ToolSource.WorkerTool(cap, "w");

    String result =
        switch (source) {
          case ToolSource.WorkerTool wt -> "worker:" + wt.workerName();
          case ToolSource.LocalTool lt -> "local:" + lt.name();
        };

    assertThat(result).isEqualTo("worker:w");
  }
}
