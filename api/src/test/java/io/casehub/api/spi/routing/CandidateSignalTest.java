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

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CandidateSignalTest {

  @Test
  void score_carriesValueAndRationale() {
    var signal = new RoutingSignal.CandidateSignal.Score(0.85, "high trust");
    assertThat(signal.value()).isEqualTo(0.85);
    assertThat(signal.rationale()).isEqualTo("high trust");
  }

  @Test
  void score_allowsNullRationale() {
    var signal = new RoutingSignal.CandidateSignal.Score(0.5, null);
    assertThat(signal.rationale()).isNull();
  }

  @Test
  void exclude_carriesReason() {
    var signal = new RoutingSignal.CandidateSignal.Exclude("phase 2b");
    assertThat(signal.reason()).isEqualTo("phase 2b");
  }

  @Test
  void escalate_carriesReasonAndRationale() {
    var signal =
        new RoutingSignal.CandidateSignal.Escalate(
            EscalationReason.NO_QUALIFIED_AGENT, "bootstrap only");
    assertThat(signal.reason()).isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
    assertThat(signal.rationale()).isEqualTo("bootstrap only");
  }

  @Test
  void sealedSwitch_exhaustive() {
    RoutingSignal.CandidateSignal signal = new RoutingSignal.CandidateSignal.Score(0.5, null);
    String result =
        switch (signal) {
          case RoutingSignal.CandidateSignal.Score s -> "score:" + s.value();
          case RoutingSignal.CandidateSignal.Exclude e -> "exclude:" + e.reason();
          case RoutingSignal.CandidateSignal.Escalate esc -> "escalate:" + esc.reason();
        };
    assertThat(result).startsWith("score:");
  }
}
