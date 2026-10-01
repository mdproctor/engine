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

import io.casehub.engine.common.spi.ImprovementBlockStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryImprovementBlockStoreContractTest extends ImprovementBlockStoreContractTest {

  private InMemoryImprovementBlockStore store;

  @Override
  protected ImprovementBlockStore createStore() {
    store = new InMemoryImprovementBlockStore();
    return store;
  }

  @Override
  protected String tenancyId() {
    return "test-tenant";
  }

  @Test
  void resetClearsAllBlocks() {
    var caseId = UUID.randomUUID();
    var improvementId = UUID.randomUUID();
    store.save(caseId, improvementId, UUID.randomUUID(), tenancyId());
    assertThat(store.isBlocked(caseId, improvementId, tenancyId())).isTrue();

    store.reset();

    assertThat(store.isBlocked(caseId, improvementId, tenancyId())).isFalse();
  }
}
