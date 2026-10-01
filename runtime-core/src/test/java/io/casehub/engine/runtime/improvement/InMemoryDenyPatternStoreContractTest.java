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

import io.casehub.engine.common.spi.DenyPatternStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryDenyPatternStoreContractTest extends DenyPatternStoreContractTest {

  private InMemoryDenyPatternStore store;

  @Override
  protected DenyPatternStore createStore() {
    store = new InMemoryDenyPatternStore();
    return store;
  }

  @Override
  protected String tenancyId() {
    return "test-tenant";
  }

  @Test
  void resetClearsAllPatterns() {
    var caseId = UUID.randomUUID();
    store.save(caseId, "pattern-a", tenancyId());
    assertThat(store.findAll(caseId, tenancyId())).isNotEmpty();

    store.reset();

    assertThat(store.findAll(caseId, tenancyId())).isEmpty();
  }
}
