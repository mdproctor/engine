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
}
