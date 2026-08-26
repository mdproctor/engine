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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.CallerIdentity;
import io.casehub.api.model.Evidence;
import io.casehub.api.model.EvidenceType;
import io.casehub.api.model.JudgmentResponse;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.VerificationContext;
import io.casehub.api.spi.VerificationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidencePresenceVerifierTest {

  private final EvidencePresenceVerifier verifier = new EvidencePresenceVerifier();

  @Test
  void acceptsWhenAllRequiredEvidencePresent() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .evidence("rationale", EvidenceType.REASONING, true)
            .evidence("ref", EvidenceType.REFERENCE, false)
            .build();
    var response = response(List.of(evidence("rationale", EvidenceType.REASONING)));
    var ctx = ctx(target);

    var result = verifier.verify(response, ctx);
    assertInstanceOf(VerificationResult.Accepted.class, result);
  }

  @Test
  void acceptsWhenOptionalEvidenceMissing() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .evidence("rationale", EvidenceType.REASONING, false)
            .build();
    var response = response(List.of());
    var ctx = ctx(target);

    var result = verifier.verify(response, ctx);
    assertInstanceOf(VerificationResult.Accepted.class, result);
  }

  @Test
  void rejectsWhenRequiredEvidenceMissing() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .evidence("rationale", EvidenceType.REASONING, true)
            .evidence("attestation", EvidenceType.ATTESTATION, true)
            .build();
    var response = response(List.of(evidence("rationale", EvidenceType.REASONING)));
    var ctx = ctx(target);

    var result = verifier.verify(response, ctx);
    assertInstanceOf(VerificationResult.InsufficientEvidence.class, result);
    var ie = (VerificationResult.InsufficientEvidence) result;
    assertEquals(1, ie.missingRequirements().size());
    assertTrue(ie.missingRequirements().contains("attestation"));
    assertTrue(ie.feedback().contains("attestation"));
  }

  @Test
  void rejectsWhenAllRequiredEvidenceMissing() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .evidence("a", EvidenceType.REASONING, true)
            .evidence("b", EvidenceType.DOCUMENT, true)
            .build();
    var response = response(List.of());
    var ctx = ctx(target);

    var result = verifier.verify(response, ctx);
    assertInstanceOf(VerificationResult.InsufficientEvidence.class, result);
    var ie = (VerificationResult.InsufficientEvidence) result;
    assertEquals(2, ie.missingRequirements().size());
  }

  @Test
  void acceptsWhenNoRequirementsDeclared() {
    var target = JudgmentTarget.forHuman().prompt("Review").build();
    var response = response(List.of());
    var ctx = ctx(target);

    var result = verifier.verify(response, ctx);
    assertInstanceOf(VerificationResult.Accepted.class, result);
  }

  @Test
  void idIsEvidencePresence() {
    assertEquals("evidence-presence", verifier.id());
  }

  private static JudgmentResponse response(List<Evidence> evidence) {
    return new JudgmentResponse(
        Map.of("decision", "approve"),
        evidence,
        new CallerIdentity("user-1", "human", null),
        Instant.now());
  }

  private static Evidence evidence(String name, EvidenceType type) {
    return new Evidence(name, type, "content", null);
  }

  private static VerificationContext ctx(JudgmentTarget target) {
    return new VerificationContext(UUID.randomUUID(), "t1", "b1", target, null, null);
  }
}
