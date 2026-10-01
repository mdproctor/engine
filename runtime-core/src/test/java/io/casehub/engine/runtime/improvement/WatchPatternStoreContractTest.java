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

import io.casehub.api.model.stigmergy.WatchPattern;
import io.casehub.engine.common.spi.WatchPatternStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class WatchPatternStoreContractTest {

  protected abstract WatchPatternStore createStore();

  protected abstract String tenancyId();

  private WatchPatternStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  protected WatchPattern makePattern(String id) {
    return new WatchPattern(id, null, null, null, null, Instant.now());
  }

  @Test
  void saveAndFindActive() {
    var caseId = UUID.randomUUID();

    store.save(caseId, makePattern("wp-1"), tenancyId());
    store.save(caseId, makePattern("wp-2"), tenancyId());

    var active = store.findActive(caseId, tenancyId());
    assertThat(active).hasSize(2);
    assertThat(active).extracting(WatchPattern::id).containsExactlyInAnyOrder("wp-1", "wp-2");
  }

  @Test
  void removeByPatternId() {
    var caseId = UUID.randomUUID();

    store.save(caseId, makePattern("wp-1"), tenancyId());
    store.save(caseId, makePattern("wp-2"), tenancyId());
    store.remove(caseId, "wp-1", tenancyId());

    var active = store.findActive(caseId, tenancyId());
    assertThat(active).hasSize(1);
    assertThat(active.get(0).id()).isEqualTo("wp-2");
  }

  @Test
  void perCaseIsolation() {
    var case1 = UUID.randomUUID();
    var case2 = UUID.randomUUID();

    store.save(case1, makePattern("wp-1"), tenancyId());
    store.save(case2, makePattern("wp-2"), tenancyId());

    assertThat(store.findActive(case1, tenancyId())).hasSize(1);
    assertThat(store.findActive(case1, tenancyId()).get(0).id()).isEqualTo("wp-1");
    assertThat(store.findActive(case2, tenancyId())).hasSize(1);
    assertThat(store.findActive(case2, tenancyId()).get(0).id()).isEqualTo("wp-2");
  }

  @Test
  void findActiveReturnsEmptyForUnknownCase() {
    assertThat(store.findActive(UUID.randomUUID(), tenancyId())).isEmpty();
  }
}
