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
package io.casehub.work.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.internal.model.TargetType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JpaPlanItemStoreTest {

  @Inject JpaPlanItemStore store;

  @Test
  @Transactional
  void save_and_findByCaseId() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();
    store.save(
        new PlanItemSaveRequest(
            caseId,
            planItemId,
            "test-binding",
            PlanItemStatus.PENDING,
            Instant.now(),
            TargetType.HUMAN_TASK,
            null,
            "test-tenant"),
        "test-tenant");
    List<PlanItemRecord> found = store.findByCaseId(caseId, "test-tenant");
    assertThat(found).hasSize(1);
    assertThat(found.get(0).status()).isEqualTo(PlanItemStatus.PENDING);
  }

  @Test
  @Transactional
  void updateStatus_updates_stored_value() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();
    store.save(
        new PlanItemSaveRequest(
            caseId,
            planItemId,
            "test-binding",
            PlanItemStatus.PENDING,
            Instant.now(),
            TargetType.HUMAN_TASK,
            null,
            "test-tenant"),
        "test-tenant");
    store.updateStatus(planItemId, PlanItemStatus.RUNNING);
    List<PlanItemRecord> found = store.findByCaseId(caseId, "test-tenant");
    assertThat(found.get(0).status()).isEqualTo(PlanItemStatus.RUNNING);
  }
}
