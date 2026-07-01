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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.ImplementationCandidate;
import io.casehub.api.spi.routing.ImplementationRoutingContext;
import io.casehub.api.spi.routing.ImplementationSelection;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustWeightedImplementationRoutingStrategyTest {

  private TrustScoreSource source;
  private TrustRoutingPolicyProvider policyProvider;
  private TrustWeightedImplementationRoutingStrategy strategy;
  private ImplementationRoutingContext ctx;

  // Default policy: threshold=0.7, minimumObservations=5, borderlineMargin=0.1, blendFactor=0.6
  private static final TrustRoutingPolicy DEFAULT_POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false, null);

  @BeforeEach
  void setUp() {
    source = mock(TrustScoreSource.class);
    policyProvider = mock(TrustRoutingPolicyProvider.class);
    strategy =
        new TrustWeightedImplementationRoutingStrategy(
            new TrustCandidateClassifier(), source, policyProvider);
    ctx = new ImplementationRoutingContext(UUID.randomUUID(), "strategy", NullNode.instance);

    when(policyProvider.forCapability("strategy")).thenReturn(DEFAULT_POLICY);
    when(source.capabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(source.decisionCount(any(), any())).thenReturn(0);
    when(source.capabilityDimensionScore(any(), any(), any())).thenReturn(OptionalDouble.empty());
  }

  // ---- Qualified candidates → highest score wins --------------------------

  @Test
  void select_qualifiedCandidates_returnsHighestTrustScore() {
    // Seed trust scores: workerA=0.9, workerB=0.6, workerC=0.4
    when(source.capabilityScore("workerA", "strategy")).thenReturn(OptionalDouble.of(0.9));
    when(source.decisionCount("workerA", "strategy")).thenReturn(15);

    when(source.capabilityScore("workerB", "strategy")).thenReturn(OptionalDouble.of(0.6));
    when(source.decisionCount("workerB", "strategy")).thenReturn(15);

    when(source.capabilityScore("workerC", "strategy")).thenReturn(OptionalDouble.of(0.4));
    when(source.decisionCount("workerC", "strategy")).thenReturn(15);

    var candidates =
        List.of(
            new ImplementationCandidate("binding-a", "workerA", "strategy"),
            new ImplementationCandidate("binding-b", "workerB", "strategy"),
            new ImplementationCandidate("binding-c", "workerC", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    assertThat(result).isInstanceOf(ImplementationSelection.Selected.class);
    var selected = (ImplementationSelection.Selected) result;
    assertThat(selected.bindingNames()).containsExactly("binding-a");
  }

  // ---- BOOTSTRAP (no history) → availability routing ----------------------

  @Test
  void select_bootstrapCandidates_returnsRunAll() {
    // All candidates have no trust history → BOOTSTRAP phase
    // workloadScore is always 1.0 (no runningJobs concept for implementations)
    // All score the same → decision is arbitrary → RunAll is correct
    var candidates =
        List.of(
            new ImplementationCandidate("binding-a", "workerA", "strategy"),
            new ImplementationCandidate("binding-b", "workerB", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    // BOOTSTRAP with equal scores → RunAll (no clear winner)
    assertThat(result).isInstanceOf(ImplementationSelection.RunAll.class);
  }

  // ---- All excluded → backstop selects first candidate --------------------

  @Test
  void select_allExcluded_selectsFirstCandidate() {
    // All candidates below threshold → all EXCLUDED_PHASE2B
    when(source.capabilityScore("workerA", "strategy")).thenReturn(OptionalDouble.of(0.3));
    when(source.decisionCount("workerA", "strategy")).thenReturn(15);

    when(source.capabilityScore("workerB", "strategy")).thenReturn(OptionalDouble.of(0.2));
    when(source.decisionCount("workerB", "strategy")).thenReturn(15);

    var candidates =
        List.of(
            new ImplementationCandidate("binding-a", "workerA", "strategy"),
            new ImplementationCandidate("binding-b", "workerB", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    assertThat(result).isInstanceOf(ImplementationSelection.Selected.class);
    var selected = (ImplementationSelection.Selected) result;
    // Backstop: select first candidate
    assertThat(selected.bindingNames()).containsExactly("binding-a");
  }

  // ---- Single candidate → RunAll shortcut ---------------------------------

  @Test
  void select_singleCandidate_returnsRunAll() {
    var candidates = List.of(new ImplementationCandidate("binding-a", "workerA", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    assertThat(result).isInstanceOf(ImplementationSelection.RunAll.class);
  }

  @Test
  void select_emptyCandidates_returnsRunAll() {
    var result = strategy.select(ctx, List.of()).await().indefinitely();

    assertThat(result).isInstanceOf(ImplementationSelection.RunAll.class);
  }

  // ---- BORDERLINE → excluded from scoring ---------------------------------

  @Test
  void select_borderlineCandidates_selectsFirstCandidate() {
    // Both candidates borderline (within margin of threshold 0.7)
    // 0.65: |0.65 - 0.7| = 0.05 ≤ 0.1 → borderline
    when(source.capabilityScore("workerA", "strategy")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("workerA", "strategy")).thenReturn(15);

    when(source.capabilityScore("workerB", "strategy")).thenReturn(OptionalDouble.of(0.75));
    when(source.decisionCount("workerB", "strategy")).thenReturn(15);

    var candidates =
        List.of(
            new ImplementationCandidate("binding-a", "workerA", "strategy"),
            new ImplementationCandidate("binding-b", "workerB", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    // All borderline → all excluded → backstop selects first
    assertThat(result).isInstanceOf(ImplementationSelection.Selected.class);
    var selected = (ImplementationSelection.Selected) result;
    assertThat(selected.bindingNames()).containsExactly("binding-a");
  }

  // ---- Mixed pool: QUALIFIED beats BORDERLINE -----------------------------

  @Test
  void select_qualifiedAndBorderline_selectsQualified() {
    // workerA: QUALIFIED (0.85, well above threshold)
    when(source.capabilityScore("workerA", "strategy")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("workerA", "strategy")).thenReturn(15);

    // workerB: BORDERLINE (0.65)
    when(source.capabilityScore("workerB", "strategy")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("workerB", "strategy")).thenReturn(15);

    var candidates =
        List.of(
            new ImplementationCandidate("binding-a", "workerA", "strategy"),
            new ImplementationCandidate("binding-b", "workerB", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    assertThat(result).isInstanceOf(ImplementationSelection.Selected.class);
    var selected = (ImplementationSelection.Selected) result;
    assertThat(selected.bindingNames()).containsExactly("binding-a");
  }

  // ---- Fallback binding — exempt from BORDERLINE exclusion ----------------

  @Test
  void select_allExcluded_withFallback_selectsFallbackBinding() {
    var fallbackPolicy = new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false, "binding-b");
    when(policyProvider.forCapability("strategy")).thenReturn(fallbackPolicy);

    when(source.capabilityScore("workerA", "strategy")).thenReturn(OptionalDouble.of(0.3));
    when(source.decisionCount("workerA", "strategy")).thenReturn(15);

    when(source.capabilityScore("workerB", "strategy")).thenReturn(OptionalDouble.of(0.2));
    when(source.decisionCount("workerB", "strategy")).thenReturn(15);

    var candidates =
        List.of(
            new ImplementationCandidate("binding-a", "workerA", "strategy"),
            new ImplementationCandidate("binding-b", "workerB", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    assertThat(result).isInstanceOf(ImplementationSelection.Selected.class);
    var selected = (ImplementationSelection.Selected) result;
    assertThat(selected.bindingNames()).containsExactly("binding-b");
  }

  @Test
  void select_borderline_fallbackExempted_selectsFallback() {
    var fallbackPolicy = new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false, "binding-b");
    when(policyProvider.forCapability("strategy")).thenReturn(fallbackPolicy);

    when(source.capabilityScore("workerA", "strategy")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("workerA", "strategy")).thenReturn(15);

    when(source.capabilityScore("workerB", "strategy")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("workerB", "strategy")).thenReturn(15);

    var candidates =
        List.of(
            new ImplementationCandidate("binding-a", "workerA", "strategy"),
            new ImplementationCandidate("binding-b", "workerB", "strategy"));

    var result = strategy.select(ctx, candidates).await().indefinitely();

    assertThat(result).isInstanceOf(ImplementationSelection.Selected.class);
    var selected = (ImplementationSelection.Selected) result;
    assertThat(selected.bindingNames()).containsExactly("binding-b");
  }
}
