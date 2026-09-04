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
package io.casehub.engine.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.graphql.dto.CompensationChainType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.repository.CaseLedgerEntryRepository;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompensationChainQueryTest {

  private CaseQueryResolver resolver;
  private CaseLedgerEntryRepository ledgerRepository;

  @BeforeEach
  void setUp() {
    resolver = new CaseQueryResolver();
    resolver.instanceRepository = mock(CaseInstanceRepository.class);
    resolver.definitionRegistry = mock(io.casehub.engine.common.spi.CaseDefinitionRegistry.class);
    resolver.runtime = mock(io.casehub.api.engine.CaseHubRuntime.class);
    resolver.planItemStore = mock(io.casehub.engine.common.spi.PlanItemStore.class);
    resolver.currentPrincipal = mock(CurrentPrincipal.class);
    ledgerRepository = mock(CaseLedgerEntryRepository.class);
    resolver.ledgerRepository = ledgerRepository;
  }

  @Test
  void compensationChainReturnsEmptyForCaseWithoutCompensation() {
    UUID caseId = UUID.randomUUID();
    when(ledgerRepository.findByCaseId(caseId)).thenReturn(List.of());

    CompensationChainType result = resolver.compensationChain(caseId);

    assertThat(result).isNotNull();
    assertThat(result.caseId()).isEqualTo(caseId);
    assertThat(result.entries()).isEmpty();
  }

  @Test
  void compensationChainFiltersToCompensationSupplementEntries() {
    UUID caseId = UUID.randomUUID();
    UUID originalEntryId = UUID.randomUUID();

    CaseLedgerEntry normalEntry = new CaseLedgerEntry();
    normalEntry.caseId = caseId;
    normalEntry.eventType = "CaseStarted";
    normalEntry.caseStatus = "RUNNING";

    CaseLedgerEntry compensationEntry = new CaseLedgerEntry();
    compensationEntry.caseId = caseId;
    compensationEntry.eventType = "CompensationStepCompleted";
    compensationEntry.caseStatus = "COMPENSATING";
    compensationEntry.supplementJson =
        "{\"COMPENSATION\":{\"originalEntryId\":\""
            + originalEntryId
            + "\",\"compensationReason\":\"Trial withdrawn\""
            + ",\"regulatoryBasis\":\"GDPR Art.17\""
            + ",\"compensationMode\":\"human-driven\"}}";

    when(ledgerRepository.findByCaseId(caseId)).thenReturn(List.of(normalEntry, compensationEntry));

    CompensationChainType result = resolver.compensationChain(caseId);

    assertThat(result.entries()).hasSize(1);
    var entry = result.entries().get(0);
    assertThat(entry.eventType()).isEqualTo("CompensationStepCompleted");
    assertThat(entry.originalEntryId()).isEqualTo(originalEntryId);
    assertThat(entry.compensationReason()).isEqualTo("Trial withdrawn");
    assertThat(entry.regulatoryBasis()).isEqualTo("GDPR Art.17");
    assertThat(entry.compensationMode()).isEqualTo("human-driven");
  }

  @Test
  void compensationChainPreservesOrdering() {
    UUID caseId = UUID.randomUUID();

    CaseLedgerEntry entry1 = new CaseLedgerEntry();
    entry1.caseId = caseId;
    entry1.sequenceNumber = 5;
    entry1.eventType = "CompensationStarted";
    entry1.caseStatus = "COMPENSATING";
    entry1.supplementJson =
        "{\"COMPENSATION\":{\"originalEntryId\":\""
            + UUID.randomUUID()
            + "\",\"compensationMode\":\"automated\"}}";

    CaseLedgerEntry entry2 = new CaseLedgerEntry();
    entry2.caseId = caseId;
    entry2.sequenceNumber = 6;
    entry2.eventType = "CompensationStepCompleted";
    entry2.caseStatus = "COMPENSATING";
    entry2.supplementJson =
        "{\"COMPENSATION\":{\"originalEntryId\":\""
            + UUID.randomUUID()
            + "\",\"compensationMode\":\"automated\"}}";

    when(ledgerRepository.findByCaseId(caseId)).thenReturn(List.of(entry1, entry2));

    CompensationChainType result = resolver.compensationChain(caseId);

    assertThat(result.entries()).hasSize(2);
    assertThat(result.entries().get(0).eventType()).isEqualTo("CompensationStarted");
    assertThat(result.entries().get(1).eventType()).isEqualTo("CompensationStepCompleted");
  }
}
