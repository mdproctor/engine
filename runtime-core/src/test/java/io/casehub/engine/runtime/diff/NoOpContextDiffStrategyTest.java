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
package io.casehub.engine.internal.diff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class NoOpContextDiffStrategyTest {

  private final NoOpContextDiffStrategy strategy = new NoOpContextDiffStrategy();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void alwaysReturnsNull_forEmptyNodes() {
    assertThat(strategy.compute(MAPPER.createObjectNode(), MAPPER.createObjectNode())).isNull();
  }

  @Test
  void alwaysReturnsNull_forNonEmptyNodes() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("status", "processing");
    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");

    assertThat(strategy.compute(before, after)).isNull();
  }

  @Test
  void alwaysReturnsNull_regardlessOfContent() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("status", "processing");
    before.put("score", 10);
    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");
    after.put("score", 99);
    after.put("newKey", "hello");

    // NoOp returns null regardless of how much the context changed
    assertThat(strategy.compute(before, after)).isNull();
  }
}
