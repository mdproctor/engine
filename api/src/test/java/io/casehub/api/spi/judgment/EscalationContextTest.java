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
package io.casehub.api.spi.judgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.casehub.api.model.JudgmentTarget;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EscalationContextTest {

  private static final JudgmentTarget TARGET =
      JudgmentTarget.builder().prompt("Assess risk").build();

  @Test
  void fullConstructor() {
    var identity = CallerIdentity.of("llm-1", "llm");
    var evidence = List.of(Evidence.of("reasoning", EvidenceType.REASONING, "Because X"));
    var responseTime = Duration.ofMillis(450);

    var ctx =
        new EscalationContext(
            UUID.randomUUID(),
            "tenant-1",
            "binding",
            TARGET,
            "reject",
            evidence,
            new VerificationResult.TrustTooLow("high", "medium"),
            1,
            3,
            null,
            identity,
            responseTime);

    assertEquals(identity, ctx.callerIdentity());
    assertEquals(1, ctx.evidence().size());
    assertEquals(Duration.ofMillis(450), ctx.responseTime());
    assertEquals(1, ctx.escalationCount());
    assertEquals(3, ctx.maxEscalations());
  }

  @Test
  void nullOptionalFields() {
    var ctx =
        new EscalationContext(
            UUID.randomUUID(),
            "tenant-1",
            "binding",
            TARGET,
            "approve",
            List.of(),
            new VerificationResult.InsufficientEvidence("missing docs", List.of("docs")),
            2,
            5,
            null,
            null,
            null);

    assertNull(ctx.callerIdentity());
    assertNull(ctx.responseTime());
    assertEquals(2, ctx.escalationCount());
  }
}
