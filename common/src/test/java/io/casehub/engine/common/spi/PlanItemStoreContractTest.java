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
package io.casehub.engine.common.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Abstract contract test — extend with a concrete impl to verify the Store SPI. */
public abstract class PlanItemStoreContractTest {

  protected static final String TEST_TENANT = "test-tenant";

  protected abstract PlanItemStore store();

  private PlanItemSaveRequest request(UUID caseId, String planItemId, TaskStatus status) {
    return PlanItemSaveRequest.primitive(
        caseId,
        planItemId,
        "my-binding",
        status,
        Instant.now(),
        TargetType.HUMAN_TASK,
        null,
        null,
        null,
        "test-worker",
        null);
  }

  @Test
  void save_and_findByCaseId() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();
    store().save(request(caseId, planItemId, TaskStatus.PENDING), TEST_TENANT);
    List<PlanItemRecord> results = store().findByCaseId(caseId, TEST_TENANT);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).planItemId()).isEqualTo(planItemId);
    assertThat(results.get(0).status()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void updateStatus_changes_stored_status() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();
    store().save(request(caseId, planItemId, TaskStatus.PENDING), TEST_TENANT);
    store().updateStatus(planItemId, TaskStatus.RUNNING);
    List<PlanItemRecord> results = store().findByCaseId(caseId, TEST_TENANT);
    assertThat(results.get(0).status()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void findDelegated_returns_only_delegated_CrossTenant_for_case() {
    UUID caseId = UUID.randomUUID();
    String delegatedId = UUID.randomUUID().toString();
    String pendingId = UUID.randomUUID().toString();
    store().save(request(caseId, delegatedId, TaskStatus.DELEGATED), TEST_TENANT);
    store().save(request(caseId, pendingId, TaskStatus.PENDING), TEST_TENANT);
    List<PlanItemRecord> results = store().findDelegatedCrossTenant(caseId);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).planItemId()).isEqualTo(delegatedId);
  }

  @Test
  void findAllDelegated_returns_delegated_across_cases() {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();
    String id1 = UUID.randomUUID().toString();
    String id2 = UUID.randomUUID().toString();
    store().save(request(case1, id1, TaskStatus.DELEGATED), TEST_TENANT);
    store().save(request(case2, id2, TaskStatus.DELEGATED), TEST_TENANT);
    List<PlanItemRecord> results = store().findAllDelegated();
    assertThat(results.stream().map(PlanItemRecord::planItemId))
        .containsExactlyInAnyOrder(id1, id2);
  }
}
