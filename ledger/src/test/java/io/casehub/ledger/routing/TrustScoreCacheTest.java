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
package io.casehub.ledger.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.routing.TrustScoreDeltaPayload;
import io.casehub.ledger.runtime.service.routing.TrustScoreFullPayload;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustScoreCacheTest {

  private ActorTrustScoreRepository trustRepo;
  private TrustScoreCache cache;

  @BeforeEach
  void setUp() {
    trustRepo = mock(ActorTrustScoreRepository.class);
    when(trustRepo.findAll()).thenReturn(List.of());
    cache = new TrustScoreCache(trustRepo);
    cache.hydrate();
  }

  // ---- Phase 0: empty cache -----------------------------------------------

  @Test
  void emptyCache_getCapabilityScore_returnsEmpty() {
    assertThat(cache.getCapabilityScore("agent-1", "research")).isEmpty();
  }

  @Test
  void emptyCache_getDecisionCount_returnsZero() {
    assertThat(cache.getDecisionCount("agent-1", "research")).isEqualTo(0);
  }

  @Test
  void emptyCache_getCapabilityDimensionScore_returnsEmpty() {
    assertThat(cache.getCapabilityDimensionScore("agent-1", "research", "thoroughness")).isEmpty();
  }

  // ---- Hydration from repository ------------------------------------------

  @Test
  void hydrate_capabilityScore_indexedCorrectly() {
    when(trustRepo.findAll()).thenReturn(List.of(capabilityScore("agent-1", "research", 0.85, 15)));
    cache.hydrate();

    assertThat(cache.getCapabilityScore("agent-1", "research")).hasValue(0.85);
    assertThat(cache.getDecisionCount("agent-1", "research")).isEqualTo(15);
  }

  @Test
  void hydrate_capabilityDimensionScore_indexedCorrectly() {
    when(trustRepo.findAll())
        .thenReturn(List.of(capabilityDimensionScore("agent-1", "research", "thoroughness", 0.78)));
    cache.hydrate();

    assertThat(cache.getCapabilityDimensionScore("agent-1", "research", "thoroughness"))
        .hasValue(0.78);
  }

  @Test
  void hydrate_globalScore_ignored() {
    when(trustRepo.findAll()).thenReturn(List.of(globalScore("agent-1", 0.9)));
    cache.hydrate();

    // GLOBAL scores are not indexed — only CAPABILITY and CAPABILITY_DIMENSION are needed for
    // routing
    assertThat(cache.getCapabilityScore("agent-1", "research")).isEmpty();
  }

  // ---- Full payload event update -------------------------------------------

  @Test
  void onFull_updatesCapabilityScore() {
    cache.onFull(
        new TrustScoreFullPayload(List.of(capabilityScore("agent-2", "analysis", 0.72, 8))));

    assertThat(cache.getCapabilityScore("agent-2", "analysis")).hasValue(0.72);
    assertThat(cache.getDecisionCount("agent-2", "analysis")).isEqualTo(8);
  }

  @Test
  void onFull_updatesCapabilityDimensionScore() {
    cache.onFull(
        new TrustScoreFullPayload(
            List.of(capabilityDimensionScore("agent-2", "analysis", "accuracy", 0.91))));

    assertThat(cache.getCapabilityDimensionScore("agent-2", "analysis", "accuracy")).hasValue(0.91);
  }

  @Test
  void onFull_overwritesPreviousValue() {
    cache.onFull(
        new TrustScoreFullPayload(List.of(capabilityScore("agent-1", "research", 0.60, 5))));
    cache.onFull(
        new TrustScoreFullPayload(List.of(capabilityScore("agent-1", "research", 0.75, 10))));

    assertThat(cache.getCapabilityScore("agent-1", "research")).hasValue(0.75);
    assertThat(cache.getDecisionCount("agent-1", "research")).isEqualTo(10);
  }

  @Test
  void onFull_ignoresGlobalRows() {
    cache.onFull(new TrustScoreFullPayload(List.of(globalScore("agent-1", 0.95))));
    assertThat(cache.getCapabilityScore("agent-1", "research")).isEmpty();
  }

  // ---- Delta event: no-op --------------------------------------------------

  @Test
  void onDelta_isNoOp_doesNotUpdateCapabilityScores() {
    // Pre-populate via onFull so we have a baseline
    cache.onFull(
        new TrustScoreFullPayload(List.of(capabilityScore("agent-1", "research", 0.80, 12))));

    // Delta carries GLOBAL scores only — no capabilityKey/dimensionKey
    cache.onDelta(new TrustScoreDeltaPayload(List.of()));

    // Capability score unchanged
    assertThat(cache.getCapabilityScore("agent-1", "research")).hasValue(0.80);
  }

  // ---- Multi-actor isolation -----------------------------------------------

  @Test
  void differentActors_isolatedScores() {
    cache.onFull(
        new TrustScoreFullPayload(
            List.of(
                capabilityScore("agent-1", "research", 0.80, 10),
                capabilityScore("agent-2", "research", 0.60, 5))));

    assertThat(cache.getCapabilityScore("agent-1", "research")).hasValue(0.80);
    assertThat(cache.getCapabilityScore("agent-2", "research")).hasValue(0.60);
    assertThat(cache.getCapabilityScore("agent-3", "research")).isEmpty();
  }

  @Test
  void sameActor_differentCapabilities_isolatedScores() {
    cache.onFull(
        new TrustScoreFullPayload(
            List.of(
                capabilityScore("agent-1", "research", 0.85, 15),
                capabilityScore("agent-1", "analysis", 0.70, 8))));

    assertThat(cache.getCapabilityScore("agent-1", "research")).hasValue(0.85);
    assertThat(cache.getCapabilityScore("agent-1", "analysis")).hasValue(0.70);
  }

  // ---- Helpers -------------------------------------------------------------

  private static ActorTrustScore capabilityScore(
      final String actorId,
      final String capabilityKey,
      final double trustScore,
      final int decisionCount) {
    final ActorTrustScore s = new ActorTrustScore();
    s.actorId = actorId;
    s.scoreType = ScoreType.CAPABILITY;
    s.capabilityKey = capabilityKey;
    s.dimensionKey = null;
    s.trustScore = trustScore;
    s.decisionCount = decisionCount;
    return s;
  }

  private static ActorTrustScore capabilityDimensionScore(
      final String actorId,
      final String capabilityKey,
      final String dimensionKey,
      final double trustScore) {
    final ActorTrustScore s = new ActorTrustScore();
    s.actorId = actorId;
    s.scoreType = ScoreType.CAPABILITY_DIMENSION;
    s.capabilityKey = capabilityKey;
    s.dimensionKey = dimensionKey;
    s.trustScore = trustScore;
    s.decisionCount = 0;
    return s;
  }

  private static ActorTrustScore globalScore(final String actorId, final double trustScore) {
    final ActorTrustScore s = new ActorTrustScore();
    s.actorId = actorId;
    s.scoreType = ScoreType.GLOBAL;
    s.capabilityKey = null;
    s.dimensionKey = null;
    s.trustScore = trustScore;
    s.decisionCount = 0;
    return s;
  }
}
