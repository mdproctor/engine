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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReActWorkerFunctionProviderTest {

  private final ReActWorkerFunctionProvider provider = new ReActWorkerFunctionProvider();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void detectsReactYamlBlock() {
    var node = MAPPER.createObjectNode();
    node.putObject("react").put("maxCycles", 10);

    assertThat(provider.handles(node)).isTrue();
  }

  @Test
  void doesNotDetectWithoutReactBlock() {
    var node = MAPPER.createObjectNode();
    node.putObject("agent").put("model", "anthropic");

    assertThat(provider.handles(node)).isFalse();
  }

  @Test
  void createsReActWorkerFunctionWithMaxCycles() {
    var node = MAPPER.createObjectNode();
    node.putObject("react").put("maxCycles", 15);

    var fn = provider.create(node);

    assertThat(fn).isInstanceOf(ReActWorkerFunction.class);
    var reactFn = (ReActWorkerFunction) fn;
    assertThat(reactFn.maxCycles()).isEqualTo(15);
  }

  @Test
  void defaultMaxCyclesWhenNotSpecified() {
    var node = MAPPER.createObjectNode();
    node.putObject("react");

    var fn = provider.create(node);
    var reactFn = (ReActWorkerFunction) fn;
    assertThat(reactFn.maxCycles()).isEqualTo(20);
  }

  @Test
  void extractsSystemPromptFromAgentBlock() {
    var node = MAPPER.createObjectNode();
    node.putObject("react");
    node.putObject("agent").put("systemPrompt", "You are a research analyst");

    var fn = provider.create(node);
    var reactFn = (ReActWorkerFunction) fn;
    assertThat(reactFn.systemPrompt()).isEqualTo("You are a research analyst");
  }

  @Test
  void emptySystemPromptWhenNoAgentBlock() {
    var node = MAPPER.createObjectNode();
    node.putObject("react");

    var fn = provider.create(node);
    var reactFn = (ReActWorkerFunction) fn;
    assertThat(reactFn.systemPrompt()).isEmpty();
  }
}
