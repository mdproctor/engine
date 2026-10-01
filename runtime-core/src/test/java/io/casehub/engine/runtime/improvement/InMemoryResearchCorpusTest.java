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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.HilQueueEntry;
import io.casehub.api.model.stigmergy.ResearchAnalysis;
import io.casehub.api.model.stigmergy.ResearchCandidate;
import io.casehub.api.model.stigmergy.ResearchFinding;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryResearchCorpusTest {

  private InMemoryResearchCorpus corpus;

  @BeforeEach
  void setUp() {
    corpus = new InMemoryResearchCorpus();
  }

  @Test
  void storeAndSearch() {
    var finding =
        new ResearchFinding(
            "technique",
            "benefits",
            "limits",
            "applicable",
            "high",
            "stability",
            "https://example.com/paper");
    var analysis = new ResearchAnalysis(List.of(finding), List.of("theme"), List.of(), List.of());
    var candidates =
        List.of(
            new ResearchCandidate(
                "https://example.com/paper", "Paper", "Abstract", "academic", Map.of()));

    corpus.store(candidates, analysis);

    var results = corpus.search("technique", "stability", 10);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).technique()).isEqualTo("technique");
  }

  @Test
  void getBySourceUrl() {
    var finding =
        new ResearchFinding(
            "technique",
            "benefits",
            "limits",
            "applicable",
            "high",
            "stability",
            "https://example.com/paper");
    var analysis = new ResearchAnalysis(List.of(finding), List.of(), List.of(), List.of());
    corpus.store(List.of(), analysis);

    assertThat(corpus.get("https://example.com/paper")).isPresent();
    assertThat(corpus.get("https://other.com")).isEmpty();
  }

  @Test
  void hilQueueLifecycle() {
    var entry =
        new HilQueueEntry(
            "https://paywalled.com",
            "Doe 2025",
            "needs full text",
            "stability",
            1,
            List.of("hyp-1"),
            Instant.now());
    corpus.addToHilQueue(entry);

    assertThat(corpus.pendingHilEntries()).hasSize(1);

    var resolved =
        new ResearchCandidate(
            "https://paywalled.com", "Full Paper", "Full abstract", "academic", Map.of());
    corpus.resolveHilEntry("https://paywalled.com", resolved);

    assertThat(corpus.pendingHilEntries()).isEmpty();
  }

  @Test
  void emptySearchReturnsEmpty() {
    assertThat(corpus.search("anything", "stability", 10)).isEmpty();
  }

  @Test
  void resetClearsAll() {
    var finding =
        new ResearchFinding(
            "technique",
            "benefits",
            "limits",
            "applicable",
            "high",
            "stability",
            "https://example.com/paper");
    var analysis = new ResearchAnalysis(List.of(finding), List.of(), List.of(), List.of());
    corpus.store(List.of(), analysis);
    corpus.reset();
    assertThat(corpus.search("technique", "stability", 10)).isEmpty();
  }
}
