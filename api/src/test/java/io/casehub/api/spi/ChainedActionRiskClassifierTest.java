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
package io.casehub.api.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.spi.RiskDecision.Autonomous;
import io.casehub.api.spi.RiskDecision.GateRequired;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.worker.api.PlannedAction;
import jakarta.enterprise.inject.Instance;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChainedActionRiskClassifierTest {

  private ChainedActionRiskClassifier chain;

  private static PlannedAction anyAction() {
    return PlannedAction.of("desc", "spend.transfer", Map.of("amount", 100));
  }

  private static ClassificationContext anyContext() {
    return new ClassificationContext(
        "w-1", UUID.randomUUID(), "tenant-1", "test-case", "cap", "binding");
  }

  @BeforeEach
  void setUp() {
    chain = new ChainedActionRiskClassifier();
  }

  @Test
  void emptyChain_returnsAutonomous() {
    chain.classifiers = unsatisfied();

    RiskDecision result = chain.classify(anyAction(), anyContext());

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  @Test
  void singleClassifier_returnsAutonomous_propagatesAutonomous() {
    chain.classifiers = instanceOf((action, context) -> new Autonomous());

    RiskDecision result = chain.classify(anyAction(), anyContext());

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  @Test
  void singleClassifier_returnsGateRequired_propagatesGateRequired() {
    final GateRequired gate =
        new GateRequired(
            "SAR filing", false, StaticSetStrategy.of("mlro"), Duration.ofHours(24), null, null);
    chain.classifiers = instanceOf((action, context) -> gate);

    RiskDecision result = chain.classify(anyAction(), anyContext());

    assertThat(result).isInstanceOf(GateRequired.class);
    assertThat(((GateRequired) result).reason()).isEqualTo("SAR filing");
    assertThat(((GateRequired) result).candidateGroups()).isInstanceOf(StaticSetStrategy.class);
    assertThat(((StaticSetStrategy) ((GateRequired) result).candidateGroups()).values())
        .containsExactly("mlro");
  }

  @Test
  void twoClassifiers_bothAutonomous_returnsAutonomous() {
    chain.classifiers =
        instanceOf((action, context) -> new Autonomous(), (action, context) -> new Autonomous());

    RiskDecision result = chain.classify(anyAction(), anyContext());

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  @Test
  void twoClassifiers_oneAutonomousOneGateRequired_returnsGateRequired() {
    chain.classifiers =
        instanceOf(
            (action, context) -> new Autonomous(),
            (action, context) ->
                new GateRequired(
                    "SUSAR filing", false, StaticSetStrategy.of("physician"), null, null, null));

    RiskDecision result = chain.classify(anyAction(), anyContext());

    assertThat(result).isInstanceOf(GateRequired.class);
    assertThat(((GateRequired) result).candidateGroups()).isInstanceOf(StaticSetStrategy.class);
    assertThat(((StaticSetStrategy) ((GateRequired) result).candidateGroups()).values())
        .containsExactly("physician");
  }

  @Test
  void twoClassifiers_fewerCandidateGroupsWins_notUnion() {
    chain.classifiers =
        instanceOf(
            (action, context) ->
                new GateRequired(
                    "AML", false, StaticSetStrategy.of("mlro"), Duration.ofHours(24), null, null),
            (action, context) ->
                new GateRequired(
                    "clinical",
                    false,
                    StaticSetStrategy.of("physician", "pharmacist"),
                    null,
                    null,
                    null));

    GateRequired result = (GateRequired) chain.classify(anyAction(), anyContext());

    assertThat(result.candidateGroups()).isInstanceOf(StaticSetStrategy.class);
    assertThat(((StaticSetStrategy) result.candidateGroups()).values()).containsExactly("mlro");
    assertThat(result.reason()).isEqualTo("AML");
  }

  @Test
  void twoClassifiers_sameGroupCount_shorterExpiresInWins() {
    chain.classifiers =
        instanceOf(
            (action, context) ->
                new GateRequired(
                    "slow", false, StaticSetStrategy.of("mlro"), Duration.ofHours(48), null, null),
            (action, context) ->
                new GateRequired(
                    "fast",
                    false,
                    StaticSetStrategy.of("analyst"),
                    Duration.ofHours(24),
                    null,
                    null));

    GateRequired result = (GateRequired) chain.classify(anyAction(), anyContext());

    assertThat(result.expiresIn()).isEqualTo(Duration.ofHours(24));
    assertThat(result.reason()).isEqualTo("fast");
  }

  @Test
  void twoClassifiers_sameGroupCount_deadlineBeatsNoDeadline() {
    chain.classifiers =
        instanceOf(
            (action, context) ->
                new GateRequired(
                    "no-deadline", false, StaticSetStrategy.of("mlro"), null, null, null),
            (action, context) ->
                new GateRequired(
                    "with-deadline",
                    false,
                    StaticSetStrategy.of("analyst"),
                    Duration.ofHours(24),
                    null,
                    null));

    GateRequired result = (GateRequired) chain.classify(anyAction(), anyContext());

    assertThat(result.expiresIn()).isEqualTo(Duration.ofHours(24));
    assertThat(result.reason()).isEqualTo("with-deadline");
  }

  @Test
  void twoClassifiers_nullCandidateGroupsVsRestricted_restrictedGroupsWins() {
    chain.classifiers =
        instanceOf(
            (action, context) -> new GateRequired("unrestricted", false, null, null, null, null),
            (action, context) ->
                new GateRequired(
                    "restricted", false, StaticSetStrategy.of("mlro"), null, null, null));

    GateRequired result = (GateRequired) chain.classify(anyAction(), anyContext());

    assertThat(result.candidateGroups()).isInstanceOf(StaticSetStrategy.class);
    assertThat(((StaticSetStrategy) result.candidateGroups()).values()).containsExactly("mlro");
    assertThat(result.reason()).isEqualTo("restricted");
  }

  @Test
  void classifierThrows_failSafeGateRequiredApplied() {
    chain.classifiers =
        instanceOf(
            (action, context) -> {
              throw new RuntimeException("DB unavailable");
            });

    RiskDecision result = chain.classify(anyAction(), anyContext());

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
            (action, context) -> {
              throw new IllegalStateException("config missing");
            });

    GateRequired result = (GateRequired) chain.classify(anyAction(), anyContext());

    assertThat(result.scope()).isNull();
    assertThat(result.expiresIn()).isNull();
  }

  @Test
  void classify_passesContextToClassifiers() {
    final ClassificationContext[] captured = {null};
    chain.classifiers =
        instanceOf(
            (action, context) -> {
              captured[0] = context;
              return new Autonomous();
            });

    ClassificationContext ctx =
        new ClassificationContext(
            "worker-x", UUID.randomUUID(), "tenant-1", "test-case", "cap", "binding");
    chain.classify(anyAction(), ctx);

    assertThat(captured[0].workerId()).isEqualTo("worker-x");
    assertThat(captured[0]).isSameAs(ctx);
  }

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
