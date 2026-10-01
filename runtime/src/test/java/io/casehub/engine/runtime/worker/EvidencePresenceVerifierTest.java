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

import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.judgment.Evidence;
import io.casehub.api.spi.judgment.EvidenceType;
import io.casehub.api.spi.judgment.VerificationContext;
import io.casehub.api.spi.judgment.VerificationResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidencePresenceVerifierTest {

  private final EvidencePresenceVerifier verifier = new EvidencePresenceVerifier();

  @Test
  void allEvidencePresent_returnsAccepted() {
    var target =
        JudgmentTarget.builder()
            .prompt("test")
            .evidenceRequirements(List.of("riskScore", "rationale"))
            .build();
    var ctx =
        new VerificationContext(
            UUID.randomUUID(),
            "t",
            "b",
            target,
            Map.of(),
            null,
            "approve",
            List.of(
                Evidence.of("riskScore", EvidenceType.METRIC, "0.8"),
                Evidence.of("rationale", EvidenceType.REASONING, "low risk")),
            null,
            null);
    assertThat(verifier.verify(ctx)).isInstanceOf(VerificationResult.Accepted.class);
  }

  @Test
  void missingEvidence_returnsInsufficientEvidence() {
    var target =
        JudgmentTarget.builder()
            .prompt("test")
            .evidenceRequirements(List.of("riskScore", "rationale", "supportingDocs"))
            .build();
    var ctx =
        new VerificationContext(
            UUID.randomUUID(),
            "t",
            "b",
            target,
            Map.of(),
            null,
            "approve",
            List.of(Evidence.of("riskScore", EvidenceType.METRIC, "0.8")),
            null,
            null);
    var result = verifier.verify(ctx);
    assertThat(result).isInstanceOf(VerificationResult.InsufficientEvidence.class);
    var ie = (VerificationResult.InsufficientEvidence) result;
    assertThat(ie.missingKeys()).containsExactlyInAnyOrder("rationale", "supportingDocs");
  }

  @Test
  void emptyRequirements_returnsAccepted() {
    var target = JudgmentTarget.builder().prompt("test").build();
    var ctx =
        new VerificationContext(
            UUID.randomUUID(), "t", "b", target, Map.of(), null, "approve", List.of(), null, null);
    assertThat(verifier.verify(ctx)).isInstanceOf(VerificationResult.Accepted.class);
  }

  @Test
  void id_returnsEvidencePresence() {
    assertThat(verifier.id()).isEqualTo("evidence-presence");
  }
}
