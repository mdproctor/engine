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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.platform.api.actor.ActorStateContributor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReactiveActorStateAggregatorTest {

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

  @Test
  void allSources_healthy_completesUni() {
    final var agg =
        new ReactiveActorStateAggregator(
            List.of(
                contributor("ledger", (id, acc) -> acc.trustScore(0.75)),
                contributor("work", (id, acc) -> {}),
                contributor("qhorus", (id, acc) -> {}),
                contributor("engine", (id, acc) -> acc.engineActiveCaseId(UUID.randomUUID()))));

    final var resp = agg.forActor("agent-x").await().indefinitely();

    assertEquals(0.75, resp.trustScore(), 0.001);
    assertEquals(4, resp.sources().size());
    assertNull(resp.sourceWarnings());
  }

  @Test
  void oneSourceThrows_excludedFromSources_othersIntact() {
    final var agg =
        new ReactiveActorStateAggregator(
            List.of(
                contributor("ledger", (id, acc) -> acc.trustScore(0.7)),
                contributor(
                    "work",
                    (id, acc) -> {
                      throw new RuntimeException("timeout");
                    })));

    final var resp = agg.forActor("agent-x").await().indefinitely();

    assertEquals(0.7, resp.trustScore(), 0.001);
    assertTrue(resp.sources().contains("ledger"));
    assertFalse(resp.sources().contains("work"));
    assertTrue(resp.sourceWarnings().containsKey("work"));
  }
}
