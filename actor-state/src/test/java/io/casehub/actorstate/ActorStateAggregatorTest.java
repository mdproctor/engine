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
package io.casehub.actorstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.platform.api.actor.ActorStateContributor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain JUnit tests for ActorStateAggregator — no CDI, uses test constructor. */
class ActorStateAggregatorTest {

  // ── Helper ────────────────────────────────────────────────────────────────

  private static ActorStateContributor contributor(
      final String name, final ThrowingContributor body) {
    return new ActorStateContributor() {
      @Override
      public String sourceName() {
        return name;
      }

      @Override
      public void contribute(final String actorId, final ActorStateAccumulator acc) {
        body.contribute(actorId, acc);
      }
    };
  }

  @FunctionalInterface
  interface ThrowingContributor {
    void contribute(String actorId, ActorStateAccumulator acc);
  }

  // ── Tests ─────────────────────────────────────────────────────────────────

  @Test
  void allSources_healthy_completesResponse() {
    final UUID caseId = UUID.randomUUID();
    final var agg =
        new ActorStateAggregator(
            List.of(
                contributor(
                    "ledger",
                    (id, acc) -> {
                      acc.trustScore(0.82);
                      acc.capabilityScore("sar-drafting", 0.79);
                    }),
                contributor(
                    "work",
                    (id, acc) ->
                        acc.workItem(UUID.randomUUID(), "title", "IN_PROGRESS", "aml", caseId)),
                contributor(
                    "qhorus",
                    (id, acc) ->
                        acc.commitment(
                            UUID.randomUUID(), UUID.randomUUID(), caseId, "OPEN", Instant.now())),
                contributor("engine", (id, acc) -> acc.engineActiveCaseId(caseId))));

    final var resp = agg.forActor("agent-x");

    assertEquals(0.82, resp.trustScore(), 0.001);
    assertEquals(0.79, resp.capabilityScores().get("sar-drafting"), 0.001);
    assertEquals(1, resp.activeWorkItems().size());
    assertEquals(1, resp.openCommitments().size());
    assertEquals(1, resp.engineActiveCaseIds().size());
    assertThat(resp.sources()).containsExactlyInAnyOrder("ledger", "work", "qhorus", "engine");
    assertNull(resp.sourceWarnings());
    assertNotNull(resp.retrievedAt());
  }

  @Test
  void oneSourceThrows_excludedFromSources_othersIntact() {
    final var agg =
        new ActorStateAggregator(
            List.of(
                contributor("ledger", (id, acc) -> acc.trustScore(0.7)),
                contributor(
                    "work",
                    (id, acc) -> {
                      throw new RuntimeException("DB down");
                    }),
                contributor("qhorus", (id, acc) -> {}),
                contributor("engine", (id, acc) -> {})));

    final var resp = agg.forActor("agent-x");

    assertEquals(0.7, resp.trustScore(), 0.001);
    assertThat(resp.sources()).doesNotContain("work");
    assertThat(resp.sources()).contains("ledger");
    assertNotNull(resp.sourceWarnings());
    assertTrue(resp.sourceWarnings().containsKey("work"));
    assertTrue(resp.activeWorkItems().isEmpty());
  }

  @Test
  void noScore_trustScoreNull_notZero() {
    final var agg =
        new ActorStateAggregator(List.of(contributor("ledger", (id, acc) -> acc.trustScore(null))));

    assertNull(agg.forActor("unknown-actor").trustScore());
  }

  @Test
  void workItemNullCallerRef_caseIdNull_noThrow() {
    final var agg =
        new ActorStateAggregator(
            List.of(
                contributor(
                    "work",
                    (id, acc) -> acc.workItem(UUID.randomUUID(), null, "ASSIGNED", null, null))));

    final var resp = agg.forActor("agent-x");
    assertEquals(1, resp.activeWorkItems().size());
    assertNull(resp.activeWorkItems().get(0).caseId());
    assertNull(resp.activeWorkItems().get(0).title());
  }

  @Test
  void sourceThatThrowsNullMessageException_stillReturns200_withClassNameInWarnings() {
    // ConcurrentHashMap rejects null values — e.getMessage() null must be guarded
    final var agg =
        new ActorStateAggregator(
            List.of(
                contributor("ledger", (id, acc) -> acc.trustScore(0.7)),
                contributor(
                    "work",
                    (id, acc) -> {
                      throw new NullPointerException(); // no message — getMessage() returns null
                    })));

    final var resp = agg.forActor("agent-x");

    assertEquals(200, 200); // no exception thrown — contract preserved
    assertNotNull(resp.sourceWarnings());
    assertTrue(resp.sourceWarnings().containsKey("work"));
    // Warning message uses class simple name, not null
    assertEquals("NullPointerException", resp.sourceWarnings().get("work"));
    assertTrue(resp.sources().contains("ledger"));
    assertFalse(resp.sources().contains("work"));
  }

  @Test
  void partialWriteContributor_partialDataVisible_sourceExcluded() {
    // Documents actual behavior: accumulator writes before a throw are NOT rolled back.
    // The contributor responsibility contract requires atomicity from contributors, not the engine.
    final var agg =
        new ActorStateAggregator(
            List.of(
                contributor(
                    "ledger",
                    (id, acc) -> {
                      acc.trustScore(0.8);
                      throw new RuntimeException("partial fail after trustScore");
                    }),
                contributor("work", (id, acc) -> {})));

    final var resp = agg.forActor("agent-x");

    assertEquals(0.8, resp.trustScore(), 0.001);
    assertFalse(resp.sources().contains("ledger"));
    assertTrue(resp.sources().contains("work"));
    assertNotNull(resp.sourceWarnings());
    assertTrue(resp.sourceWarnings().containsKey("ledger"));
  }

  @Test
  void unknownActor_validEmptyResponse_allSourcesPresent() {
    final var agg =
        new ActorStateAggregator(
            List.of(
                contributor("ledger", (id, acc) -> {}),
                contributor("work", (id, acc) -> {}),
                contributor("qhorus", (id, acc) -> {}),
                contributor("engine", (id, acc) -> {})));

    final var resp = agg.forActor("unknown");

    assertNull(resp.trustScore());
    assertTrue(resp.activeWorkItems().isEmpty());
    assertTrue(resp.openCommitments().isEmpty());
    assertTrue(resp.engineActiveCaseIds().isEmpty());
    assertEquals(4, resp.sources().size());
    assertNull(resp.sourceWarnings());
  }
}
