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

import io.casehub.api.model.stigmergy.ProposalFilteringSummary;
import io.casehub.api.model.stigmergy.TickTrace;
import io.casehub.api.model.stigmergy.TickTrace.GateResult;
import io.casehub.api.model.stigmergy.TickTrace.GateResult.GateVerdict;
import io.casehub.api.model.stigmergy.TickTrace.TickOutcome;
import io.casehub.api.model.stigmergy.TickTrace.TickTrigger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TickTraceTest {

  @Test
  void traceRecordsGateResults() {
    var trace =
        new TickTrace(
            UUID.randomUUID(),
            Instant.now(),
            TickTrigger.EVENT_DRIVEN,
            List.of(
                new GateResult("evolution_enabled", GateVerdict.PASSED, null),
                new GateResult(
                    "circuit_breaker_check", GateVerdict.BLOCKED, "health below threshold")),
            null);

    assertThat(trace.gates()).hasSize(2);
    assertThat(trace.gates().get(0).verdict()).isEqualTo(GateVerdict.PASSED);
    assertThat(trace.gates().get(1).verdict()).isEqualTo(GateVerdict.BLOCKED);
    assertThat(trace.gates().get(1).reason()).isEqualTo("health below threshold");
  }

  @Test
  void noProposalOutcomeCarriesReason() {
    var outcome = new TickOutcome.NoProposal("evolution disabled");
    assertThat(outcome.reason()).isEqualTo("evolution disabled");
  }

  @Test
  void proposalOutcomeIncludesFilteringSummary() {
    var summary = new ProposalFilteringSummary(Map.of("signal-consensus", 10), 8, 7, 5, 5, 4, 3, 3);
    var outcome = new TickOutcome.ProposalGenerated(3, summary);

    assertThat(outcome.goalCount()).isEqualTo(3);
    assertThat(outcome.filtering().proposalsBySource()).containsEntry("signal-consensus", 10);
    assertThat(outcome.filtering().proposed()).isEqualTo(3);
  }

  @Test
  void heartbeatOutcomeIsDistinct() {
    TickOutcome outcome = new TickOutcome.Heartbeat();
    assertThat(outcome).isInstanceOf(TickOutcome.Heartbeat.class);
  }

  @Test
  void sealedOutcomePermitsExactlyThreeTypes() {
    var permitted = TickOutcome.class.getPermittedSubclasses();
    assertThat(permitted).hasSize(3);
  }

  @Test
  void tickTriggerHasTwoValues() {
    assertThat(TickTrigger.values()).hasSize(2);
    assertThat(TickTrigger.valueOf("EVENT_DRIVEN")).isEqualTo(TickTrigger.EVENT_DRIVEN);
    assertThat(TickTrigger.valueOf("TIMER")).isEqualTo(TickTrigger.TIMER);
  }
}
