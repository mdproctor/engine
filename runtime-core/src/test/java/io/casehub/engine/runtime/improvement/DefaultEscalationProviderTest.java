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

import io.casehub.api.model.stigmergy.CategoryEscalationRules;
import io.casehub.api.model.stigmergy.EscalationContext;
import io.casehub.api.model.stigmergy.EscalationPolicy;
import io.casehub.api.model.stigmergy.EscalationTrigger.EscalationLayer;
import io.casehub.api.model.stigmergy.WatchPattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultEscalationProviderTest {

  private DefaultEscalationProvider provider;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    provider = new DefaultEscalationProvider();
    caseId = UUID.randomUUID();
  }

  @Test
  void categoryAlwaysEscalateTriggers() {
    var rules = new CategoryEscalationRules(List.of("security"), List.of());
    var policy = new EscalationPolicy(rules, null);
    var context = makeContext("security", null);

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of());

    assertThat(result.escalate()).isTrue();
    assertThat(result.triggers()).anyMatch(t -> t.layer() == EscalationLayer.CATEGORY_RULE);
  }

  @Test
  void neverEscalateSuppressesCategoryRuleOnly() {
    var rules = new CategoryEscalationRules(List.of("lint-fix"), List.of("lint-fix"));
    var policy = new EscalationPolicy(rules, null);
    var context = makeContext("lint-fix", null);

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of());

    assertThat(result.triggers()).noneMatch(t -> t.layer() == EscalationLayer.CATEGORY_RULE);
  }

  @Test
  void watchPatternTriggers() {
    var policy = new EscalationPolicy(null, null);
    var context = makeContext("lint-fix", "auth-module");
    var pattern = new WatchPattern("w1", null, "auth-module", null, null, Instant.now());

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of(pattern));

    assertThat(result.escalate()).isTrue();
    assertThat(result.triggers()).anyMatch(t -> t.layer() == EscalationLayer.WATCH_PATTERN);
  }

  @Test
  void watchPatternNotSuppressedByNeverEscalate() {
    var rules = new CategoryEscalationRules(List.of(), List.of("lint-fix"));
    var policy = new EscalationPolicy(rules, null);
    var context = makeContext("lint-fix", "auth-module");
    var pattern = new WatchPattern("w1", null, "auth-module", null, null, Instant.now());

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of(pattern));

    assertThat(result.escalate()).isTrue();
    assertThat(result.triggers()).anyMatch(t -> t.layer() == EscalationLayer.WATCH_PATTERN);
  }

  @Test
  void watchPatternMatchesByCategory() {
    var policy = new EscalationPolicy(null, null);
    var context = makeContext("security", null);
    var pattern = new WatchPattern("w1", "security", null, null, null, Instant.now());

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of(pattern));

    assertThat(result.escalate()).isTrue();
  }

  @Test
  void watchPatternMatchesByMinEstimatedSize() {
    var policy = new EscalationPolicy(null, null);
    var context = new EscalationContext("recipe", null, null, null, 100, Map.of());
    var pattern = new WatchPattern("w1", null, null, null, 50, Instant.now());

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of(pattern));

    assertThat(result.escalate()).isTrue();
  }

  @Test
  void noTriggersNoEscalation() {
    var policy = new EscalationPolicy(null, null);
    var context = makeContext("lint-fix", null);

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of());

    assertThat(result.escalate()).isFalse();
    assertThat(result.triggers()).isEmpty();
  }

  @Test
  void confidenceScoreIsInRange() {
    var policy = new EscalationPolicy(null, null);
    var context = makeContext("recipe", null);

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of());

    assertThat(result.confidence()).isBetween(0.0, 1.0);
  }

  @Test
  void multipleLayersCanTriggerTogether() {
    var rules = new CategoryEscalationRules(List.of("security"), List.of());
    var policy = new EscalationPolicy(rules, null);
    var context = makeContext("security", "auth-module");
    var pattern = new WatchPattern("w1", null, "auth-module", null, null, Instant.now());

    var result =
        provider.evaluate(
            caseId, "t1", CodeEvolutionStages.RESEARCH_SCOPE, context, policy, List.of(pattern));

    assertThat(result.escalate()).isTrue();
    assertThat(result.triggers()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(result.triggers()).anyMatch(t -> t.layer() == EscalationLayer.CATEGORY_RULE);
    assertThat(result.triggers()).anyMatch(t -> t.layer() == EscalationLayer.WATCH_PATTERN);
  }

  private EscalationContext makeContext(String category, String areaId) {
    return new EscalationContext(category, areaId, null, null, null, Map.of());
  }
}
