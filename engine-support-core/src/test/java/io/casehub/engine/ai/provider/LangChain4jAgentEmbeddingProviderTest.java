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
package io.casehub.engine.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

class LangChain4jAgentEmbeddingProviderTest {

  @Test
  void embed_delegatesToEmbeddingModel_returnsFloatArray() {
    final EmbeddingModel model = mock(EmbeddingModel.class);
    final float[] expected = {0.1f, 0.2f, 0.3f};
    when(model.embed(any(String.class))).thenReturn(Response.from(Embedding.from(expected)));

    final LangChain4jAgentEmbeddingProvider provider = new LangChain4jAgentEmbeddingProvider(model);

    final float[] result = provider.embed("sar-drafting agent for financial crime analysis");

    assertThat(result).containsExactly(expected);
    verify(model, times(1)).embed("sar-drafting agent for financial crime analysis");
  }

  @Test
  void embed_emptyText_returnsEmbeddingFromModel() {
    final EmbeddingModel model = mock(EmbeddingModel.class);
    when(model.embed(any(String.class)))
        .thenReturn(Response.from(Embedding.from(new float[] {0.0f})));

    final LangChain4jAgentEmbeddingProvider provider = new LangChain4jAgentEmbeddingProvider(model);

    assertThat(provider.embed("")).isNotNull();
  }
}
