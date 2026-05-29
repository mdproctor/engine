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

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * {@link AgentEmbeddingProvider} backed by the Quarkus LangChain4j {@link EmbeddingModel}.
 *
 * <p>Activates when {@code casehub-engine-ai} is on the classpath together with a Quarkus
 * LangChain4j embedding provider (e.g. {@code quarkus-langchain4j-openai}, {@code
 * quarkus-langchain4j-ollama}). Configure the model via standard {@code quarkus.langchain4j.*}
 * properties.
 *
 * <p>Thread-safe: LangChain4j {@link EmbeddingModel} implementations are stateless and designed for
 * concurrent use. Callers invoke this from the Mutiny worker pool — never from the Vert.x IO
 * thread.
 */
@ApplicationScoped
public class LangChain4jAgentEmbeddingProvider implements AgentEmbeddingProvider {

  private final EmbeddingModel embeddingModel;

  @Inject
  public LangChain4jAgentEmbeddingProvider(final EmbeddingModel embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  @Override
  public float[] embed(final String text) {
    return embeddingModel.embed(text).content().vector();
  }
}
