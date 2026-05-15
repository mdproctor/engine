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
package io.casehub.api.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ContextDiffStrategyContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void interface_hasComputeMethod() throws Exception {
    assertThat(ContextDiffStrategy.class.getMethod("compute", JsonNode.class, JsonNode.class))
        .isNotNull();
  }

  @Test
  void compute_returningNull_isValidNoOpContract() {
    ContextDiffStrategy noOp = (before, after) -> null;
    JsonNode node = MAPPER.createObjectNode();
    assertThat(noOp.compute(node, node)).isNull();
  }

  @Test
  void compute_canReturnNonNull() {
    ContextDiffStrategy passThrough = (before, after) -> after;
    JsonNode node = MAPPER.createObjectNode();
    assertThat(passThrough.compute(node, node)).isSameAs(node);
  }
}
