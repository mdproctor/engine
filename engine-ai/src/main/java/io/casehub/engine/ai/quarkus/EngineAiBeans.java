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
package io.casehub.engine.ai.quarkus;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.engine.ai.provider.LangChain4jAgentEmbeddingProvider;
import io.casehub.engine.ai.routing.EmbeddingCache;
import io.casehub.engine.ai.routing.SemanticSignalProvider;
import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EngineAiBeans {

  @Produces
  @ApplicationScoped
  LangChain4jAgentEmbeddingProvider langChain4jAgentEmbeddingProvider(
      EmbeddingModel embeddingModel) {
    return new LangChain4jAgentEmbeddingProvider(embeddingModel);
  }

  @Produces
  @ApplicationScoped
  EmbeddingCache embeddingCache(
      @ConfigProperty(name = "casehub.engine.ai.embedding-cache.max-size", defaultValue = "500")
          int maxSize) {
    return new EmbeddingCache(maxSize);
  }

  @Produces
  @ApplicationScoped
  SemanticSignalProvider semanticSignalProvider(
      AgentEmbeddingProvider embeddingProvider,
      EmbeddingCache embeddingCache,
      JQEvaluator jqEvaluator,
      @ConfigProperty(name = "casehub.routing.semantic.context-jq", defaultValue = "tostring")
          String contextSummaryJq) {
    return new SemanticSignalProvider(
        embeddingProvider, embeddingCache, jqEvaluator, contextSummaryJq);
  }
}
