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
package io.casehub.ledger.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerDecisionEntryTest {

  @Test
  void domainContentBytes_includesAllFiveFields() {
    WorkerDecisionEntry entry = new WorkerDecisionEntry();
    entry.workerId = "sar-drafter";
    entry.capabilityTag = "sar-drafting";
    entry.caseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    entry.trustScoreAtRouting = 0.85;
    entry.thresholdApplied = 0.7;

    String result = new String(entry.domainContentBytes(), StandardCharsets.UTF_8);

    assertEquals("sar-drafter|sar-drafting|00000000-0000-0000-0000-000000000001|0.85|0.7", result);
  }

  @Test
  void domainContentBytes_nullFieldsProduceEmptySegments() {
    WorkerDecisionEntry entry = new WorkerDecisionEntry();

    String result = new String(entry.domainContentBytes(), StandardCharsets.UTF_8);

    assertEquals("||||", result);
  }

  @Test
  void domainContentBytes_nullTrustScoreAndThreshold() {
    WorkerDecisionEntry entry = new WorkerDecisionEntry();
    entry.workerId = "researcher";
    entry.capabilityTag = "research";
    entry.caseId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    entry.trustScoreAtRouting = null;
    entry.thresholdApplied = null;

    String result = new String(entry.domainContentBytes(), StandardCharsets.UTF_8);

    assertEquals("researcher|research|00000000-0000-0000-0000-000000000002||", result);
  }
}
