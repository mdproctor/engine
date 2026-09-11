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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSpecificationBuilderTest {

  @Test
  void buildsToolSpecFromWorkerTool() {
    var cap = new Capability("web-search", ".query", ".results", "Search the web for information");
    var tool = new ToolSource.WorkerTool(cap, "search-worker");

    var specs = ToolSpecificationBuilder.buildAll(List.of(tool));

    assertThat(specs).hasSize(1);
    assertThat(specs.getFirst().name()).isEqualTo("web-search");
    assertThat(specs.getFirst().description()).isEqualTo("Search the web for information");
  }

  @Test
  void buildsToolSpecFromLocalTool() {
    var tool =
        new ToolSource.LocalTool(
            "calculate",
            "Run a calculation",
            args -> Map.of("result", 42),
            Map.of("expression", Map.of("type", "string")));

    var specs = ToolSpecificationBuilder.buildAll(List.of(tool));

    assertThat(specs).hasSize(1);
    assertThat(specs.getFirst().name()).isEqualTo("calculate");
    assertThat(specs.getFirst().description()).isEqualTo("Run a calculation");
  }

  @Test
  void buildsToolMapKeyedByName() {
    var cap = new Capability("search", ".", ".", "Search");
    var wt = new ToolSource.WorkerTool(cap, "searcher");
    var lt = new ToolSource.LocalTool("calc", "Calculate", args -> Map.of(), Map.of());

    var map = ToolSpecificationBuilder.buildToolMap(List.of(wt, lt));

    assertThat(map).containsKeys("search", "calc");
    assertThat(map.get("search")).isInstanceOf(ToolSource.WorkerTool.class);
    assertThat(map.get("calc")).isInstanceOf(ToolSource.LocalTool.class);
  }

  @Test
  void extractsFieldNamesFromJqExpression() {
    var fields = ToolSpecificationBuilder.extractFieldNames(".query");
    assertThat(fields).containsExactly("query");
  }

  @Test
  void extractsMultipleFieldNames() {
    var fields = ToolSpecificationBuilder.extractFieldNames("{ query: .query, limit: .limit }");
    assertThat(fields).containsExactly("query", "limit");
  }

  @Test
  void identityJqReturnsEmptyFields() {
    var fields = ToolSpecificationBuilder.extractFieldNames(".");
    assertThat(fields).isEmpty();
  }

  @Test
  void workerToolWithDescriptionButNoSchema() {
    var cap = new Capability("analyse", ".", ".", "Analyse documents");
    var tool = new ToolSource.WorkerTool(cap, "analyser");

    var specs = ToolSpecificationBuilder.buildAll(List.of(tool));

    assertThat(specs.getFirst().name()).isEqualTo("analyse");
    assertThat(specs.getFirst().description()).isEqualTo("Analyse documents");
  }
}
