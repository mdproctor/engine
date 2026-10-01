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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class ImprovementBlockStoreContractTest {

  protected abstract ImprovementBlockStore createStore();

  protected abstract String tenancyId();

  private ImprovementBlockStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  @Test
  void saveAndIsBlocked() {
    var caseId = UUID.randomUUID();
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();

    store.save(caseId, improvementId, blockerId, tenancyId());

    assertThat(store.isBlocked(caseId, improvementId, tenancyId())).isTrue();
  }

  @Test
  void blockedByReturnsBlockerUuid() {
    var caseId = UUID.randomUUID();
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();

    store.save(caseId, improvementId, blockerId, tenancyId());

    assertThat(store.blockedBy(caseId, improvementId, tenancyId())).isEqualTo(blockerId);
  }

  @Test
  void removeUnblocks() {
    var caseId = UUID.randomUUID();
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();

    store.save(caseId, improvementId, blockerId, tenancyId());
    store.remove(caseId, improvementId, tenancyId());

    assertThat(store.isBlocked(caseId, improvementId, tenancyId())).isFalse();
    assertThat(store.blockedBy(caseId, improvementId, tenancyId())).isNull();
  }

  @Test
  void unblockedByDefault() {
    var caseId = UUID.randomUUID();
    var improvementId = UUID.randomUUID();

    assertThat(store.isBlocked(caseId, improvementId, tenancyId())).isFalse();
  }

  @Test
  void blockedByReturnsNullWhenNotBlocked() {
    var caseId = UUID.randomUUID();
    var improvementId = UUID.randomUUID();

    assertThat(store.blockedBy(caseId, improvementId, tenancyId())).isNull();
  }

  @Test
  void perCaseIsolation() {
    var case1 = UUID.randomUUID();
    var case2 = UUID.randomUUID();
    var improvementId = UUID.randomUUID();
    var blockerId = UUID.randomUUID();

    store.save(case1, improvementId, blockerId, tenancyId());

    assertThat(store.isBlocked(case1, improvementId, tenancyId())).isTrue();
    assertThat(store.isBlocked(case2, improvementId, tenancyId())).isFalse();
  }
}
