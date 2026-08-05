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
package io.casehub.engine.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.MemoryRetrievalConfig;
import io.casehub.api.model.RetrievedMemory;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryQuery;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentMemoryRetrieverTest {

  private CaseMemoryStore store;
  private AgentMemoryRetriever retriever;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    store = mock(CaseMemoryStore.class);
    Instance<CaseMemoryStore> storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(store);
    retriever = new AgentMemoryRetriever(storeInstance);
  }

  @Test
  void returnsEmptyWhenDisabled() {
    var def = CaseDefinition.builder().namespace("ns").name("test").version("1.0").build();
    var result = retriever.retrieve("agent-1", "tenant-1", "analysis", def);
    assertThat(result).isEmpty();
  }

  @Test
  void retrievesFromConfiguredDomains() {
    var expMemory = memory("exp-1", "experience memory", "experience");
    var refMemory = memory("ref-1", "reflection insight", "reflection");

    when(store.query(any(MemoryQuery.class)))
        .thenAnswer(
            inv -> {
              MemoryQuery q = inv.getArgument(0);
              if (q.domain().name().equals("experience")) return List.of(expMemory);
              if (q.domain().name().equals("reflection")) return List.of(refMemory);
              return List.of();
            });

    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .memoryRetrieval(
                new MemoryRetrievalConfig(true, 10, Set.of("experience", "reflection")))
            .build();

    var result = retriever.retrieve("agent-1", "tenant-1", "analysis", def);
    assertThat(result).hasSize(2);
    assertThat(result.stream().map(RetrievedMemory::domain))
        .containsExactlyInAnyOrder("experience", "reflection");
  }

  @Test
  void truncatesToMaxMemories() {
    var memories = new java.util.ArrayList<Memory>();
    for (int i = 0; i < 20; i++) {
      memories.add(memory("mem-" + i, "memory " + i, "experience"));
    }
    when(store.query(any(MemoryQuery.class))).thenReturn(memories);

    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .memoryRetrieval(new MemoryRetrievalConfig(true, 5, Set.of("experience")))
            .build();

    var result = retriever.retrieve("agent-1", "tenant-1", "cap", def);
    assertThat(result).hasSize(5);
  }

  @Test
  void roundRobinInterleavesAcrossDomains() {
    var expMemories =
        List.of(
            memory("exp-0", "exp-0", "experience"),
            memory("exp-1", "exp-1", "experience"),
            memory("exp-2", "exp-2", "experience"));
    var refMemories =
        List.of(
            memory("ref-0", "ref-0", "reflection"),
            memory("ref-1", "ref-1", "reflection"),
            memory("ref-2", "ref-2", "reflection"));

    when(store.query(any(MemoryQuery.class)))
        .thenAnswer(
            inv -> {
              MemoryQuery q = inv.getArgument(0);
              if (q.domain().name().equals("experience")) return expMemories;
              if (q.domain().name().equals("reflection")) return refMemories;
              return List.of();
            });

    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .memoryRetrieval(new MemoryRetrievalConfig(true, 6, Set.of("experience", "reflection")))
            .build();

    var result = retriever.retrieve("agent-1", "tenant-1", "cap", def);
    assertThat(result).hasSize(6);
    assertThat(result.get(0).domain()).isNotEqualTo(result.get(1).domain());
  }

  @Test
  @SuppressWarnings("unchecked")
  void returnsEmptyWhenStoreUnavailable() {
    Instance<CaseMemoryStore> unavailable = mock(Instance.class);
    when(unavailable.isResolvable()).thenReturn(false);
    var noopRetriever = new AgentMemoryRetriever(unavailable);

    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .memoryRetrieval(MemoryRetrievalConfig.defaults())
            .build();

    var result = noopRetriever.retrieve("agent-1", "tenant-1", "cap", def);
    assertThat(result).isEmpty();
  }

  @Test
  void returnsEmptyOnStoreException() {
    when(store.query(any(MemoryQuery.class))).thenThrow(new RuntimeException("store error"));

    var def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .memoryRetrieval(new MemoryRetrievalConfig(true, 10, Set.of("experience")))
            .build();

    var result = retriever.retrieve("agent-1", "tenant-1", "cap", def);
    assertThat(result).isEmpty();
  }

  private Memory memory(String id, String text, String domain) {
    return new Memory(
        id,
        "agent-1",
        new MemoryDomain(domain),
        "tenant-1",
        null,
        text,
        Map.of(),
        Instant.now(),
        0.5);
  }
}
