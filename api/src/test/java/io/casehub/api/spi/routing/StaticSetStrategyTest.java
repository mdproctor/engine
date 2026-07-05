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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StaticSetStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void idIsStatic() {
    var strategy = StaticSetStrategy.of("a", "b");
    assertThat(strategy.id()).isEqualTo("static");
  }

  @Test
  void evaluateReturnsFixedSet() {
    var strategy = StaticSetStrategy.of("compliance-team", "legal");
    var ctx = new CandidateSetContext(MAPPER.createObjectNode());
    Set<String> result = strategy.evaluate(ctx).await().indefinitely();
    assertThat(result).containsExactlyInAnyOrder("compliance-team", "legal");
  }

  @Test
  void evaluateIgnoresContext() {
    var strategy = StaticSetStrategy.of("group-a");
    var ctx = new CandidateSetContext(MAPPER.createObjectNode().put("irrelevant", "data"));
    Set<String> result = strategy.evaluate(ctx).await().indefinitely();
    assertThat(result).containsExactly("group-a");
  }

  @Test
  void defensiveCopyOfInput() {
    var strategy = StaticSetStrategy.of("a", "b");
    Set<String> result =
        strategy
            .evaluate(new CandidateSetContext(MAPPER.createObjectNode()))
            .await()
            .indefinitely();
    assertThat(result).isUnmodifiable();
  }
}
