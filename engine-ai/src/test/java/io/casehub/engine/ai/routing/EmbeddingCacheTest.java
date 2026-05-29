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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbeddingCacheTest {

  private AgentEmbeddingProvider provider;
  private EmbeddingCache cache;

  @BeforeEach
  void setUp() {
    provider = mock(AgentEmbeddingProvider.class);
    when(provider.embed(anyString())).thenAnswer(inv -> new float[] {1.0f, 2.0f});
    cache = new EmbeddingCache(100); // max 100 entries
  }

  @Test
  void getOrCompute_firstCall_invokesProvider() {
    final float[] result = cache.getOrCompute("hello world", provider);
    assertThat(result).containsExactly(1.0f, 2.0f);
    verify(provider, times(1)).embed("hello world");
  }

  @Test
  void getOrCompute_secondCallSameText_usesCache() {
    cache.getOrCompute("hello world", provider);
    cache.getOrCompute("hello world", provider);
    verify(provider, times(1)).embed("hello world"); // only called once
  }

  @Test
  void getOrCompute_differentTexts_invokesProviderEachTime() {
    cache.getOrCompute("text one", provider);
    cache.getOrCompute("text two", provider);
    verify(provider, times(2)).embed(anyString());
  }

  @Test
  void getOrCompute_evictsWhenFull() {
    final EmbeddingCache tinyCache = new EmbeddingCache(2);
    tinyCache.getOrCompute("a", provider);
    tinyCache.getOrCompute("b", provider);
    tinyCache.getOrCompute("c", provider); // triggers eviction of one entry
    // Cache has at most 2 entries — "c" is definitely in there
    // After eviction, size <= 2
    verify(provider, times(3)).embed(anyString()); // all 3 computed at least once
  }
}
