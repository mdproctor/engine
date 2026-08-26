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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JudgmentTargetTest {

  @Test
  void humanJudgmentBuilderCreatesTarget() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review the dosage modification")
            .candidateGroups(Set.of("physicians"))
            .title("Dosage Review")
            .outcomes(Set.of("approve", "reject"))
            .evidence("rationale", EvidenceType.REASONING, true)
            .verifier("evidence-presence")
            .expiresIn(Duration.ofHours(24))
            .build();

    assertNotNull(target);
    assertEquals("Review the dosage modification", target.prompt());
    assertInstanceOf(CallerConfig.Human.class, target.callerConfig());
    var human = (CallerConfig.Human) target.callerConfig();
    assertEquals(Set.of("approve", "reject"), human.outcomes());
    assertEquals("Dosage Review", human.title());
    assertEquals(1, target.evidenceRequirements().size());
    assertEquals("rationale", target.evidenceRequirements().get(0).name());
    assertTrue(target.evidenceRequirements().get(0).required());
    assertEquals(VerificationMode.SYNCHRONOUS, target.verificationMode());
    assertEquals("evidence-presence", target.verifierStrategy());
    assertEquals(Duration.ofHours(24), target.expiresIn());
  }

  @Test
  void llmJudgmentBuilderCreatesTarget() {
    var target =
        JudgmentTarget.forLlm()
            .prompt("Evaluate the analysis quality")
            .model("anthropic")
            .modelName("claude-sonnet-4-20250514")
            .systemPrompt("You are a quality reviewer")
            .verifier("schema-validation")
            .build();

    assertNotNull(target);
    assertInstanceOf(CallerConfig.Llm.class, target.callerConfig());
    var llm = (CallerConfig.Llm) target.callerConfig();
    assertEquals("anthropic", llm.model());
    assertEquals("claude-sonnet-4-20250514", llm.modelName());
  }

  @Test
  void a2aJudgmentBuilderCreatesTarget() {
    var target =
        JudgmentTarget.forA2A()
            .prompt("Verify compliance")
            .endpoint("https://compliance-agent.example.com")
            .skill("aml-check")
            .streaming(true)
            .build();

    assertNotNull(target);
    assertInstanceOf(CallerConfig.A2A.class, target.callerConfig());
    var a2a = (CallerConfig.A2A) target.callerConfig();
    assertEquals("https://compliance-agent.example.com", a2a.endpoint());
    assertTrue(a2a.streaming());
  }

  @Test
  void anyCallerBuilderCreatesTarget() {
    var target = JudgmentTarget.forAny().prompt("Review this").trustPolicy("high-trust").build();

    assertNotNull(target);
    assertInstanceOf(CallerConfig.Any.class, target.callerConfig());
    assertEquals("high-trust", target.trustPolicy());
  }

  @Test
  void multipleEvidenceRequirements() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .evidence("rationale", EvidenceType.REASONING, true)
            .evidence("source-doc", EvidenceType.DOCUMENT, false)
            .evidence("attestation", EvidenceType.ATTESTATION, true)
            .build();

    assertEquals(3, target.evidenceRequirements().size());
    assertTrue(target.evidenceRequirements().get(0).required());
    assertFalse(target.evidenceRequirements().get(1).required());
    assertTrue(target.evidenceRequirements().get(2).required());
  }

  @Test
  void evidenceRequirementsListIsImmutable() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .evidence("rationale", EvidenceType.REASONING, true)
            .build();

    assertThrows(
        UnsupportedOperationException.class,
        () ->
            target
                .evidenceRequirements()
                .add(new EvidenceRequirement("x", EvidenceType.DOCUMENT, false)));
  }

  @Test
  void defaultVerificationModeIsSynchronous() {
    var target = JudgmentTarget.forAny().prompt("Review").build();
    assertEquals(VerificationMode.SYNCHRONOUS, target.verificationMode());
  }

  @Test
  void judgmentResponsePreservesFields() {
    var evidence = List.of(new Evidence("rationale", EvidenceType.REASONING, "Because...", null));
    var caller = new CallerIdentity("user-1", "human", 0.85);
    var now = Instant.now();
    var response = new JudgmentResponse(Map.of("decision", "approve"), evidence, caller, now);

    assertEquals(1, response.evidence().size());
    assertEquals("user-1", response.callerIdentity().callerId());
    assertEquals(0.85, response.callerIdentity().trustScore());
    assertEquals(now, response.responseTime());
  }

  @Test
  void judgmentResponseEvidenceListIsImmutable() {
    var evidence =
        new java.util.ArrayList<>(List.of(new Evidence("r", EvidenceType.REASONING, "text", null)));
    var response =
        new JudgmentResponse(null, evidence, new CallerIdentity("u", "human", null), Instant.now());

    assertThrows(
        UnsupportedOperationException.class,
        () -> response.evidence().add(new Evidence("x", EvidenceType.DOCUMENT, "y", null)));
  }

  @Test
  void bindingTargetSealedPermitsIncludesJudgmentTarget() {
    var target = JudgmentTarget.forAny().prompt("test").build();
    assertInstanceOf(BindingTarget.class, target);
  }
}
