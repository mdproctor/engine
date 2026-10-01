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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExperienceSignalProviderTest {

  private final ExperienceSignalProvider provider = new ExperienceSignalProvider();

  @Test
  void id_isExperience() {
    assertThat(provider.id()).isEqualTo("experience");
  }

  @Test
  void noExperiences_returnsNull() {
    var result = provider.evaluate(ctx(List.of()), List.of(candidate("a")));
    assertThat(result).isNull();
  }

  @Test
  void nullExperiences_returnsNull() {
    var result = provider.evaluate(ctx(null), List.of(candidate("a")));
    assertThat(result).isNull();
  }

  private static AgentCandidate candidate(String id) {
    return new AgentCandidate(id, Set.of(), 0, AgentHealth.READY, null, null, null);
  }

  private static AgentRoutingContext ctx(
      List<io.casehub.api.spi.routing.RetrievedExperience> experiences) {
    return new AgentRoutingContext(
        UUID.randomUUID(), "cap", NullNode.getInstance(), "t1", experiences, null, null);
  }
}
