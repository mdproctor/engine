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

import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingPromptSectionContractTest {

  @Test
  void interface_hasRenderMethod_returningNullableString() throws Exception {
    final var method =
        RoutingPromptSection.class.getMethod("render", AgentRoutingContext.class, List.class);
    assertThat(method).isNotNull();
    assertThat(method.getReturnType()).isEqualTo(String.class);
  }

  @Test
  void implementation_canReturnNull() {
    final RoutingPromptSection section = (ctx, eligible) -> null;

    final String result = section.render(context(), candidates());

    assertThat(result).isNull();
  }

  @Test
  void implementation_canReturnContent() {
    final RoutingPromptSection section =
        (ctx, eligible) -> "Historical context: 3 similar cases found";

    final String result = section.render(context(), candidates());

    assertThat(result).isEqualTo("Historical context: 3 similar cases found");
  }

  @Test
  void implementation_receivesContextAndCandidates() {
    final AgentRoutingContext ctx = context();
    final List<AgentCandidate> candidates = candidates();

    final RoutingPromptSection section =
        (c, e) -> "capability=%s agents=%d".formatted(c.capabilityName(), e.size());

    final String result = section.render(ctx, candidates);

    assertThat(result).isEqualTo("capability=analysis agents=1");
  }

  private static AgentRoutingContext context() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "analysis", NullNode.instance, "test-tenant", List.of(), null, null);
  }

  private static List<AgentCandidate> candidates() {
    return List.of(
        new AgentCandidate("agent-1", Set.of("analysis"), 0, AgentHealth.READY, null, null, null));
  }
}
