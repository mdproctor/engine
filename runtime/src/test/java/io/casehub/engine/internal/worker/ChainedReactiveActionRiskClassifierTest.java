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
package io.casehub.engine.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.PlannedAction;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.RiskDecision.Autonomous;
import io.casehub.api.spi.RiskDecision.GateRequired;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChainedReactiveActionRiskClassifierTest {

  private ChainedReactiveActionRiskClassifier chain;

  private static PlannedAction anyAction() {
    return PlannedAction.of("desc", "spend.transfer", Map.of("amount", 100))
        .withIdentity("w-1", UUID.randomUUID());
  }

  @BeforeEach
  void setUp() {
    chain = new ChainedReactiveActionRiskClassifier();
  }

  // --- Empty chain ---

  @Test
  void emptyChain_returnsAutonomous() {
    chain.classifiers = unsatisfied();

    RiskDecision result = chain.classify(anyAction()).await().indefinitely();

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  // --- Single classifier ---

  @Test
  void singleClassifier_returnsAutonomous_propagatesAutonomous() {
    chain.classifiers = instanceOf(action -> new Autonomous());

    RiskDecision result = chain.classify(anyAction()).await().indefinitely();

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  @Test
  void singleClassifier_returnsGateRequired_propagatesGateRequired() {
    final GateRequired gate =
        new GateRequired("SAR filing", false, List.of("mlro"), Duration.ofHours(24), null);
    chain.classifiers = instanceOf(action -> gate);

    RiskDecision result = chain.classify(anyAction()).await().indefinitely();

    assertThat(result).isInstanceOf(GateRequired.class);
    assertThat(((GateRequired) result).reason()).isEqualTo("SAR filing");
    assertThat(((GateRequired) result).candidateGroups()).containsExactly("mlro");
  }

  // --- Two classifiers, merge semantics ---

  @Test
  void twoClassifiers_bothAutonomous_returnsAutonomous() {
    chain.classifiers = instanceOf(action -> new Autonomous(), action -> new Autonomous());

    RiskDecision result = chain.classify(anyAction()).await().indefinitely();

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  @Test
  void twoClassifiers_oneAutonomousOneGateRequired_returnsGateRequired() {
    chain.classifiers =
        instanceOf(
            action -> new Autonomous(),
            action -> new GateRequired("SUSAR filing", false, List.of("physician"), null, null));

    RiskDecision result = chain.classify(anyAction()).await().indefinitely();

    assertThat(result).isInstanceOf(GateRequired.class);
    assertThat(((GateRequired) result).candidateGroups()).containsExactly("physician");
  }

  @Test
  void twoClassifiers_fewerCandidateGroupsWins_notUnion() {
    // AML returns ["mlro"] (1 group), clinical returns ["physician", "pharmacist"] (2 groups)
    // Narrower (fewer groups) wins entirely — union ["mlro","physician","pharmacist"] is wrong
    chain.classifiers =
        instanceOf(
            action -> new GateRequired("AML", false, List.of("mlro"), Duration.ofHours(24), null),
            action ->
                new GateRequired(
                    "clinical", false, List.of("physician", "pharmacist"), null, null));

    GateRequired result = (GateRequired) chain.classify(anyAction()).await().indefinitely();

    assertThat(result.candidateGroups()).containsExactly("mlro");
    assertThat(result.reason()).isEqualTo("AML");
  }

  @Test
  void twoClassifiers_sameGroupCount_shorterExpiresInWins() {
    chain.classifiers =
        instanceOf(
            action -> new GateRequired("slow", false, List.of("mlro"), Duration.ofHours(48), null),
            action ->
                new GateRequired("fast", false, List.of("analyst"), Duration.ofHours(24), null));

    GateRequired result = (GateRequired) chain.classify(anyAction()).await().indefinitely();

    assertThat(result.expiresIn()).isEqualTo(Duration.ofHours(24));
    assertThat(result.reason()).isEqualTo("fast");
  }

  @Test
  void twoClassifiers_sameGroupCount_deadlineBeatsNoDeadline() {
    chain.classifiers =
        instanceOf(
            action -> new GateRequired("no-deadline", false, List.of("mlro"), null, null),
            action ->
                new GateRequired(
                    "with-deadline", false, List.of("analyst"), Duration.ofHours(24), null));

    GateRequired result = (GateRequired) chain.classify(anyAction()).await().indefinitely();

    // expiresIn non-null is more restrictive than null
    assertThat(result.expiresIn()).isEqualTo(Duration.ofHours(24));
    assertThat(result.reason()).isEqualTo("with-deadline");
  }

  @Test
  void twoClassifiers_nullCandidateGroupsVsRestricted_restrictedGroupsWins() {
    // null candidateGroups means "no restriction" — fewer restrictions = effectively more groups
    chain.classifiers =
        instanceOf(
            action -> new GateRequired("unrestricted", false, null, null, null),
            action -> new GateRequired("restricted", false, List.of("mlro"), null, null));

    GateRequired result = (GateRequired) chain.classify(anyAction()).await().indefinitely();

    // ["mlro"] (1 group) beats null (Integer.MAX_VALUE groups) — narrower wins
    assertThat(result.candidateGroups()).containsExactly("mlro");
    assertThat(result.reason()).isEqualTo("restricted");
  }

  // --- Classifier throws → fail-safe ---

  @Test
  void classifierThrows_failSafeGateRequiredApplied() {
    chain.classifiers =
        instanceOf(
            action -> {
              throw new RuntimeException("DB unavailable");
            });

    RiskDecision result = chain.classify(anyAction()).await().indefinitely();

    assertThat(result).isInstanceOf(GateRequired.class);
    GateRequired gate = (GateRequired) result;
    assertThat(gate.reason()).contains("Classifier error");
    assertThat(gate.reversible()).isTrue();
    assertThat(gate.candidateGroups()).isNull();
  }

  @Test
  void classifierThrows_failSafeHasNullScope() {
    chain.classifiers =
        instanceOf(
            action -> {
              throw new IllegalStateException("config missing");
            });

    GateRequired result = (GateRequired) chain.classify(anyAction()).await().indefinitely();

    assertThat(result.scope()).isNull();
    assertThat(result.expiresIn()).isNull();
  }

  // --- Enrichment — classifier receives workerId and caseId ---

  @Test
  void classify_receivesEnrichedPlannedAction_withWorkerIdAndCaseId() {
    final PlannedAction[] captured = {null};
    chain.classifiers =
        instanceOf(
            action -> {
              captured[0] = action;
              return new Autonomous();
            });

    UUID caseId = UUID.randomUUID();
    PlannedAction enriched =
        PlannedAction.of("desc", "type", Map.of()).withIdentity("worker-x", caseId);
    chain.classify(enriched).await().indefinitely();

    assertThat(captured[0].workerId()).isEqualTo("worker-x");
    assertThat(captured[0].caseId()).isEqualTo(caseId);
  }

  // --- Helpers ---

  @SuppressWarnings("unchecked")
  private static Instance<ActionRiskClassifier> unsatisfied() {
    Instance<ActionRiskClassifier> inst = mock(Instance.class);
    when(inst.isUnsatisfied()).thenReturn(true);
    return inst;
  }

  @SuppressWarnings("unchecked")
  private static Instance<ActionRiskClassifier> instanceOf(ActionRiskClassifier... classifiers) {
    Instance<ActionRiskClassifier> inst = mock(Instance.class);
    when(inst.isUnsatisfied()).thenReturn(false);
    when(inst.spliterator())
        .thenReturn(Spliterators.spliteratorUnknownSize(List.of(classifiers).iterator(), 0));
    return inst;
  }
}
