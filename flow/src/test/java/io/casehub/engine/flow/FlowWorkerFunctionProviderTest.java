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
package io.casehub.engine.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class FlowWorkerFunctionProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void handles_workers_with_do_block() {
    var provider = new FlowWorkerFunctionProvider();
    ObjectNode node = MAPPER.createObjectNode();
    node.putArray("do");
    node.putObject("document")
        .put("dsl", "1.0.0")
        .put("name", "test")
        .put("namespace", "test")
        .put("version", "1.0.0");
    assertThat(provider.handles(node)).isTrue();
  }

  @Test
  void does_not_handle_workers_without_do_block() {
    var provider = new FlowWorkerFunctionProvider();
    ObjectNode node = MAPPER.createObjectNode().put("name", "test");
    assertThat(provider.handles(node)).isFalse();
  }

  @Test
  void creates_flow_worker_function_with_explicit_document() {
    var provider = new FlowWorkerFunctionProvider();
    ObjectNode node = MAPPER.createObjectNode();
    node.putObject("document")
        .put("dsl", "1.0.0")
        .put("name", "test")
        .put("namespace", "test")
        .put("version", "1.0.0");
    node.putArray("do");

    var fn = provider.create(node);
    assertThat(fn).isInstanceOf(FlowWorkerFunction.class);
    assertThat(((FlowWorkerFunction) fn).workflow()).isNotNull();
  }

  @Test
  void creates_flow_worker_function_with_generated_document() {
    var provider = new FlowWorkerFunctionProvider();
    ObjectNode node = MAPPER.createObjectNode();
    // Minimal valid workflow — empty do array
    node.putArray("do");

    var fn = provider.create(node);
    assertThat(fn).isInstanceOf(FlowWorkerFunction.class);
    assertThat(((FlowWorkerFunction) fn).workflow()).isNotNull();
    // Document is generated with default values — verify non-null
  }

  @Test
  void throws_on_invalid_workflow_syntax() {
    var provider = new FlowWorkerFunctionProvider();
    ObjectNode node = MAPPER.createObjectNode();
    node.putObject("document").put("dsl", "99.0.0"); // invalid DSL version
    node.putArray("do");

    assertThatThrownBy(() -> provider.create(node))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Failed to parse workflow definition");
  }
}
