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
package io.casehub.engine.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.internal.model.PlanItemRecord;
import io.casehub.engine.internal.model.PlanItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Abstract contract test — extend with a concrete impl to verify the Store SPI. */
public abstract class PlanItemStoreContractTest {

  protected abstract PlanItemStore store();

  @Test
  void save_and_findByCaseId() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();
    store().save(caseId, planItemId, "my-binding", PlanItemStatus.PENDING, Instant.now());
    List<PlanItemRecord> results = store().findByCaseId(caseId);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).planItemId()).isEqualTo(planItemId);
    assertThat(results.get(0).status()).isEqualTo(PlanItemStatus.PENDING);
  }

  @Test
  void updateStatus_changes_stored_status() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();
    store().save(caseId, planItemId, "my-binding", PlanItemStatus.PENDING, Instant.now());
    store().updateStatus(planItemId, PlanItemStatus.RUNNING);
    List<PlanItemRecord> results = store().findByCaseId(caseId);
    assertThat(results.get(0).status()).isEqualTo(PlanItemStatus.RUNNING);
  }
}
