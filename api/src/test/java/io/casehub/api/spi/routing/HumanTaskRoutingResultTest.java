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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HumanTaskRoutingResultTest {

  @Test
  void enriched_defensiveCopies() {
    var groups = new HashSet<>(Set.of("g1"));
    var scores = new HashMap<String, Double>();
    scores.put("u1", 0.8);
    var result = new HumanTaskRoutingResult.Enriched(groups, Set.of("u1"), scores);
    groups.add("g2");
    scores.put("u2", 0.5);
    assertThat(result.candidateGroups()).doesNotContain("g2");
    assertThat(result.candidateScores()).doesNotContainKey("u2");
  }

  @Test
  void unchanged_isInstanceOf() {
    HumanTaskRoutingResult result = new HumanTaskRoutingResult.Unchanged();
    assertThat(result).isInstanceOf(HumanTaskRoutingResult.Unchanged.class);
  }

  @Test
  void escalated_carriesReason() {
    var result = new HumanTaskRoutingResult.Escalated("constraint violation");
    assertThat(result.reason()).isEqualTo("constraint violation");
  }

  @Test
  void sealedExhaustive() {
    HumanTaskRoutingResult result = new HumanTaskRoutingResult.Unchanged();
    String label =
        switch (result) {
          case HumanTaskRoutingResult.Enriched e -> "enriched";
          case HumanTaskRoutingResult.Unchanged u -> "unchanged";
          case HumanTaskRoutingResult.Escalated e -> "escalated";
        };
    assertThat(label).isEqualTo("unchanged");
  }
}
