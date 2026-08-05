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
package io.casehub.engine.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticSignalProviderTest {

  private AgentEmbeddingProvider embeddingProvider;
  private EmbeddingCache embeddingCache;
  private JQEvaluator jqEvaluator;
  private SemanticSignalProvider provider;

  @BeforeEach
  void setUp() {
    embeddingProvider = mock(AgentEmbeddingProvider.class);
    embeddingCache = mock(EmbeddingCache.class);
    jqEvaluator = mock(JQEvaluator.class);
    provider =
        new SemanticSignalProvider(embeddingProvider, embeddingCache, jqEvaluator, "tostring");
  }

  @Test
  void id_isSemantic() {
    assertThat(provider.id()).isEqualTo("semantic");
  }

  @Test
  void noCandidatesWithDescriptor_returnsNull() {
    when(embeddingCache.getOrCompute(anyString(), any())).thenReturn(new float[] {1.0f});
    var result = provider.evaluate(ctx(), List.of(candidateWithoutDescriptor("a")));
    assertThat(result).isNull();
  }

  @Test
  void embeddingServiceFailure_returnsNull() {
    when(embeddingCache.getOrCompute(anyString(), any()))
        .thenThrow(new RuntimeException("unavailable"));
    var result = provider.evaluate(ctx(), List.of(candidateWithDescriptor("a")));
    assertThat(result).isNull();
  }

  @Test
  void candidateWithDescriptor_returnsScore() {
    float[] queryVec = {1.0f, 0.0f};
    float[] docVec = {0.707f, 0.707f};
    when(embeddingCache.getOrCompute(anyString(), any())).thenReturn(queryVec, docVec);

    var result = provider.evaluate(ctx(), List.of(candidateWithDescriptor("a")));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Score.class);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal).value()).isBetween(0.0, 1.0);
  }

  private static AgentCandidate candidateWithoutDescriptor(String id) {
    return new AgentCandidate(id, Set.of(), 0, AgentHealth.READY, null, null, null);
  }

  private static AgentCandidate candidateWithDescriptor(String id) {
    var descriptor =
        AgentDescriptor.builder().agentId(id).name(id).slot("test").tenancyId("t1").build();
    return new AgentCandidate(id, Set.of(), 0, AgentHealth.READY, descriptor, null, null);
  }

  private static AgentRoutingContext ctx() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "cap", NullNode.getInstance(), "t1", List.of(), null, null);
  }
}
