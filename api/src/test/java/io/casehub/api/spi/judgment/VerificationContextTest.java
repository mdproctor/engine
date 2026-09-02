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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.JudgmentTarget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerificationContextTest {

  private static final JudgmentTarget TARGET =
      JudgmentTarget.builder().prompt("Review this").build();

  @Test
  void fullConstructor() {
    var identity = CallerIdentity.of("agent-7", "a2a");
    var evidence =
        List.of(
            Evidence.of("score", EvidenceType.METRIC, "0.95"),
            Evidence.of("report", EvidenceType.DOCUMENT, "full report text"));
    var responseTime = Duration.ofSeconds(3);

    var ctx =
        new VerificationContext(
            UUID.randomUUID(),
            "tenant-1",
            "binding",
            TARGET,
            Map.of(),
            null,
            "approve",
            evidence,
            identity,
            responseTime);

    assertEquals(identity, ctx.callerIdentity());
    assertEquals(2, ctx.evidence().size());
    assertEquals("score", ctx.evidence().get(0).name());
    assertEquals(Duration.ofSeconds(3), ctx.responseTime());
  }

  @Test
  void nullCallerIdentityAndResponseTime() {
    var ctx =
        new VerificationContext(
            UUID.randomUUID(),
            "tenant-1",
            "binding",
            TARGET,
            Map.of(),
            null,
            "approve",
            List.of(),
            null,
            null);

    assertNull(ctx.callerIdentity());
    assertNull(ctx.responseTime());
    assertTrue(ctx.evidence().isEmpty());
  }
}
