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
package io.casehub.engine.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.blocks.agentic.model.PatternType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PatternWorkerFunctionProviderTest {

  private PatternWorkerFunctionProvider provider;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    provider = new PatternWorkerFunctionProvider();
    mapper = new ObjectMapper();
  }

  @Test
  void handlesWorkerNodeWithPatternBlock() {
    ObjectNode node = mapper.createObjectNode();
    node.putObject("pattern").put("type", "debate");
    assertThat(provider.handles(node)).isTrue();
  }

  @Test
  void doesNotHandleNodeWithoutPatternBlock() {
    ObjectNode node = mapper.createObjectNode();
    node.putObject("agent");
    assertThat(provider.handles(node)).isFalse();
  }

  @Test
  void createsPatternWorkerFunctionFromYaml() {
    ObjectNode node = mapper.createObjectNode();
    ObjectNode pattern = node.putObject("pattern");
    pattern.put("type", "debate");
    pattern.put("checkpointing", true);

    var fn = provider.create(node);
    assertThat(fn).isInstanceOf(PatternWorkerFunction.class);

    var patternFn = (PatternWorkerFunction) fn;
    assertThat(patternFn.patternType()).isEqualTo(PatternType.DEBATE);
    assertThat(patternFn.checkpointingEnabled()).isTrue();
    assertThat(patternFn.model()).isNull();
  }

  @Test
  void defaultsCheckpointingToFalse() {
    ObjectNode node = mapper.createObjectNode();
    node.putObject("pattern").put("type", "sequence");

    var fn = (PatternWorkerFunction) provider.create(node);
    assertThat(fn.checkpointingEnabled()).isFalse();
  }

  @Test
  void defaultsPatternTypeToSequence() {
    ObjectNode node = mapper.createObjectNode();
    node.putObject("pattern");

    var fn = (PatternWorkerFunction) provider.create(node);
    assertThat(fn.patternType()).isEqualTo(PatternType.SEQUENCE);
  }

  @Test
  void parsesConstraintsFromPatternBlock() {
    ObjectNode node = mapper.createObjectNode();
    ObjectNode pattern = node.putObject("pattern");
    pattern.put("type", "htn");
    ObjectNode constraints = pattern.putObject("constraints");
    constraints.put("timeBudget", "PT30M");
    constraints.put("resourceLimit", 3);

    var fn = (PatternWorkerFunction) provider.create(node);
    assertThat(fn.planningConstraints()).isNotNull();
    assertThat(fn.planningConstraints().timeBudget()).isEqualTo(java.time.Duration.ofMinutes(30));
    assertThat(fn.planningConstraints().resourceLimit()).isEqualTo(3);
  }

  @Test
  void constraintsNullWhenAbsent() {
    ObjectNode node = mapper.createObjectNode();
    node.putObject("pattern").put("type", "debate");

    var fn = (PatternWorkerFunction) provider.create(node);
    assertThat(fn.planningConstraints()).isNull();
  }
}
