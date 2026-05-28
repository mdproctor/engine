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
package io.casehub.engine.ai.spi;

/**
 * SPI for text embedding used by {@link io.casehub.engine.ai.routing.SemanticAgentRoutingStrategy}.
 *
 * <p>Implementations MUST be {@code @ApplicationScoped} (or equivalent) and thread-safe — {@link
 * #embed(String)} may be called concurrently from multiple routing decisions.
 *
 * <p>No {@code @DefaultBean} is provided. If {@code casehub-engine-ai} is on the classpath without
 * a provider, CDI fails at startup with an unsatisfied dependency error — this is intentional.
 * Semantic routing without an embedding model is a misconfiguration. A LangChain4j-backed
 * implementation is tracked in engine#381.
 *
 * <p>Engine#380 tracks embedding vector caching (per workerId + capabilityName).
 */
public interface AgentEmbeddingProvider {

  /**
   * Embed text to a float vector.
   *
   * <p>May block on network IO (embedding service call). Callers invoke this method from a worker
   * thread — never from the Vert.x IO thread.
   *
   * @param text the text to embed; never null or empty
   * @return the embedding vector; all implementations must return vectors of the same dimension
   */
  float[] embed(String text);

  /**
   * Cosine similarity between two vectors. Returns {@code 0.0} for zero vectors (no NaN).
   *
   * @param a first vector
   * @param b second vector; must be the same length as {@code a}
   * @return similarity in [-1, 1]; {@code 0.0} if either vector is the zero vector
   */
  static double cosineSimilarity(final float[] a, final float[] b) {
    double dot = 0.0;
    double magA = 0.0;
    double magB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      magA += (double) a[i] * a[i];
      magB += (double) b[i] * b[i];
    }
    if (magA == 0.0 || magB == 0.0) {
      return 0.0;
    }
    return dot / (Math.sqrt(magA) * Math.sqrt(magB));
  }
}
