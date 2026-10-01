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

import io.casehub.api.model.stigmergy.GatePolicy;
import io.casehub.api.model.stigmergy.GatePolicy.GateMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatePolicyTest {

  @Test
  void defaultModeIsAutoForAllStages() {
    var policy = new GatePolicy(null, null);

    assertThat(policy.effectiveMode(CodeEvolutionStages.PR_REVIEW)).isEqualTo(GateMode.AUTO);
    assertThat(policy.effectiveMode(CodeEvolutionStages.RESEARCH_SCOPE)).isEqualTo(GateMode.AUTO);
    assertThat(policy.effectiveMode(CodeEvolutionStages.HYPOTHESIS_APPROVAL))
        .isEqualTo(GateMode.AUTO);
    assertThat(policy.effectiveMode(CodeEvolutionStages.IMPLEMENTATION_PLAN))
        .isEqualTo(GateMode.AUTO);
  }

  @Test
  void customModeOverridesDefault() {
    var policy =
        new GatePolicy(
            Map.of(
                CodeEvolutionStages.RESEARCH_SCOPE,
                GateMode.GATED,
                CodeEvolutionStages.PR_REVIEW,
                GateMode.GATED),
            null);

    assertThat(policy.effectiveMode(CodeEvolutionStages.RESEARCH_SCOPE)).isEqualTo(GateMode.GATED);
    assertThat(policy.effectiveMode(CodeEvolutionStages.PR_REVIEW)).isEqualTo(GateMode.GATED);
    assertThat(policy.effectiveMode(CodeEvolutionStages.HYPOTHESIS_APPROVAL))
        .isEqualTo(GateMode.AUTO);
  }

  @Test
  void anyStringKeyAccepted() {
    var policy = new GatePolicy(Map.of(CodeEvolutionStages.SEARCH, GateMode.GATED), null);
    assertThat(policy.effectiveMode(CodeEvolutionStages.SEARCH)).isEqualTo(GateMode.GATED);
  }

  @Test
  void defaultTimeoutIs1440Minutes() {
    var policy = new GatePolicy(null, null);
    assertThat(policy.effectiveGateTimeoutMinutes()).isEqualTo(1440);
  }

  @Test
  void customTimeout() {
    var policy = new GatePolicy(null, 60);
    assertThat(policy.effectiveGateTimeoutMinutes()).isEqualTo(60);
  }

  @Test
  void notifyModeAccepted() {
    var policy =
        new GatePolicy(Map.of(CodeEvolutionStages.HYPOTHESIS_APPROVAL, GateMode.NOTIFY), null);

    assertThat(policy.effectiveMode(CodeEvolutionStages.HYPOTHESIS_APPROVAL))
        .isEqualTo(GateMode.NOTIFY);
  }
}
