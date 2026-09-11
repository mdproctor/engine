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

class CaseLedgerEntryTest {

  @Test
  void domainContentBytes_includesAllFourFields() {
    CaseLedgerEntry entry = new CaseLedgerEntry();
    entry.caseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    entry.commandType = "StartCase";
    entry.eventType = "CaseStarted";
    entry.caseStatus = "RUNNING";

    String result = new String(entry.domainContentBytes(), StandardCharsets.UTF_8);

    assertEquals("00000000-0000-0000-0000-000000000001|StartCase|CaseStarted|RUNNING", result);
  }

  @Test
  void domainContentBytes_nullFieldsProduceEmptySegments() {
    CaseLedgerEntry entry = new CaseLedgerEntry();
    entry.caseId = null;
    entry.commandType = null;
    entry.eventType = null;
    entry.caseStatus = null;

    String result = new String(entry.domainContentBytes(), StandardCharsets.UTF_8);

    assertEquals("|||", result);
  }

  @Test
  void domainContentBytes_partialNulls() {
    CaseLedgerEntry entry = new CaseLedgerEntry();
    entry.caseId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    entry.commandType = null;
    entry.eventType = "CaseCompleted";
    entry.caseStatus = null;

    String result = new String(entry.domainContentBytes(), StandardCharsets.UTF_8);

    assertEquals("00000000-0000-0000-0000-000000000002||CaseCompleted|", result);
  }
}
