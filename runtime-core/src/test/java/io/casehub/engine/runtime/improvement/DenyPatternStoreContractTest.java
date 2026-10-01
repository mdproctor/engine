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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class DenyPatternStoreContractTest {

  protected abstract DenyPatternStore createStore();

  protected abstract String tenancyId();

  private DenyPatternStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  @Test
  void saveAndFindAll() {
    var caseId = UUID.randomUUID();

    store.save(caseId, "pattern-a", tenancyId());
    store.save(caseId, "pattern-b", tenancyId());

    assertThat(store.findAll(caseId, tenancyId()))
        .containsExactlyInAnyOrder("pattern-a", "pattern-b");
  }

  @Test
  void remove() {
    var caseId = UUID.randomUUID();

    store.save(caseId, "pattern-a", tenancyId());
    store.save(caseId, "pattern-b", tenancyId());
    store.remove(caseId, "pattern-a", tenancyId());

    assertThat(store.findAll(caseId, tenancyId())).containsExactly("pattern-b");
  }

  @Test
  void perCaseIsolation() {
    var case1 = UUID.randomUUID();
    var case2 = UUID.randomUUID();

    store.save(case1, "pattern-a", tenancyId());
    store.save(case2, "pattern-b", tenancyId());

    assertThat(store.findAll(case1, tenancyId())).containsExactly("pattern-a");
    assertThat(store.findAll(case2, tenancyId())).containsExactly("pattern-b");
  }

  @Test
  void duplicateSaveIsIdempotent() {
    var caseId = UUID.randomUUID();

    store.save(caseId, "pattern-a", tenancyId());
    store.save(caseId, "pattern-a", tenancyId());

    assertThat(store.findAll(caseId, tenancyId())).containsExactly("pattern-a");
  }

  @Test
  void findAllReturnsEmptyForUnknownCase() {
    assertThat(store.findAll(UUID.randomUUID(), tenancyId())).isEmpty();
  }
}
