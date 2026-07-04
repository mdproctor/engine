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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateSetStrategyContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void strategyExtendsNamedStrategy() {
    CandidateSetStrategy strategy =
        new CandidateSetStrategy() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public Uni<Set<String>> evaluate(CandidateSetContext context) {
            return Uni.createFrom().item(Set.of("group-a"));
          }
        };
    assertThat(strategy).isInstanceOf(io.casehub.platform.api.routing.NamedStrategy.class);
    assertThat(strategy.id()).isEqualTo("test");
  }

  @Test
  void evaluateReturnsCandidateSet() {
    CandidateSetStrategy strategy =
        new CandidateSetStrategy() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public Uni<Set<String>> evaluate(CandidateSetContext context) {
            return Uni.createFrom().item(Set.of("group-a", "group-b"));
          }
        };

    JsonNode context = MAPPER.createObjectNode();
    Set<String> result = strategy.evaluate(new CandidateSetContext(context)).await().indefinitely();
    assertThat(result).containsExactlyInAnyOrder("group-a", "group-b");
  }

  @Test
  void candidateSetContextWithConfig() {
    JsonNode node = MAPPER.createObjectNode();
    var ctx = new CandidateSetContext(node, Map.of("session", "irb"));
    assertThat(ctx.config()).containsEntry("session", "irb");
  }

  @Test
  void candidateSetContextDefaultEmptyConfig() {
    JsonNode node = MAPPER.createObjectNode();
    var ctx = new CandidateSetContext(node);
    assertThat(ctx.config()).isEmpty();
  }
}
