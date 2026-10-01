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

import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.engine.common.spi.ConductorInboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryConductorInboxRepositoryContractTest extends ConductorInboxRepositoryContractTest {

  private InMemoryConductorInboxRepository store;

  @Override
  protected ConductorInboxRepository createStore() {
    store = new InMemoryConductorInboxRepository();
    return store;
  }

  @Override
  protected String tenancyId() {
    return "test-tenant";
  }

  @Test
  void resetClearsAllEntries() {
    var caseId = UUID.randomUUID();
    store.save(
        makeEntry(
            caseId, "e1", CodeEvolutionStages.RESEARCH_SCOPE, ConductorInboxEntry.Status.PENDING),
        tenancyId());
    assertThat(store.findAll(caseId, tenancyId())).isNotEmpty();

    store.reset();

    assertThat(store.findAll(caseId, tenancyId())).isEmpty();
  }
}
