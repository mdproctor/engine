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
package io.casehub.blackboard.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.blackboard.plan.CasePlanModel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlackboardRegistryTest {

  private BlackboardRegistry registry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    caseId = UUID.randomUUID();
  }

  @Test
  void getOrCreate_returnsPlanModel() {
    CasePlanModel model = registry.getOrCreate(caseId);
    assertThat(model).isNotNull();
  }

  @Test
  void getOrCreate_returnsSameInstanceOnRepeatCall() {
    CasePlanModel first = registry.getOrCreate(caseId);
    CasePlanModel second = registry.getOrCreate(caseId);
    assertThat(first).isSameAs(second);
  }

  @Test
  void get_returnsEmptyForUnknownCase() {
    assertThat(registry.get(UUID.randomUUID())).isEmpty();
  }

  @Test
  void get_returnsPresentAfterGetOrCreate() {
    CasePlanModel model = registry.getOrCreate(caseId);
    assertThat(registry.get(caseId)).contains(model);
  }

  @Test
  void indexWorkerForCompletion_andGetPlanItemId_roundTrip() {
    registry.getOrCreate(caseId);
    String planItemId = UUID.randomUUID().toString();
    registry.indexWorkerForCompletion(caseId, "worker-a", planItemId);
    assertThat(registry.getPlanItemId(caseId, "worker-a")).contains(planItemId);
  }

  @Test
  void getPlanItemId_returnsEmptyForUnknownCase() {
    assertThat(registry.getPlanItemId(UUID.randomUUID(), "worker-a")).isEmpty();
  }

  @Test
  void getPlanItemId_returnsEmptyForUnknownWorker() {
    registry.getOrCreate(caseId);
    assertThat(registry.getPlanItemId(caseId, "unknown-worker")).isEmpty();
  }

  @Test
  void markConfigured_returnsTrueFirstTime() {
    registry.getOrCreate(caseId);
    assertThat(registry.markConfigured(caseId)).isTrue();
  }

  @Test
  void markConfigured_returnsFalseOnSubsequentCall() {
    registry.getOrCreate(caseId);
    registry.markConfigured(caseId);
    assertThat(registry.markConfigured(caseId)).isFalse();
  }

  @Test
  void evict_removesAllState() {
    registry.getOrCreate(caseId);
    registry.indexWorkerForCompletion(caseId, "worker-a", "plan-item-1");
    registry.markConfigured(caseId);

    registry.evict(caseId);

    assertThat(registry.get(caseId)).isEmpty();
    assertThat(registry.getPlanItemId(caseId, "worker-a")).isEmpty();
    // explicit recreate before checking configured flag — markConfigured no-ops on missing entry
    registry.getOrCreate(caseId);
    assertThat(registry.markConfigured(caseId)).isTrue();
  }

  @Test
  void evict_isIdempotent() {
    registry.getOrCreate(caseId);
    registry.evict(caseId);
    registry.evict(caseId); // must not throw
    assertThat(registry.get(caseId)).isEmpty();
  }

  @Test
  void markConfigured_returnsFalseWhenCaseNotPresent() {
    // no getOrCreate — entry does not exist
    assertThat(registry.markConfigured(UUID.randomUUID())).isFalse();
  }

  @Test
  void indexWorkerForCompletion_noOpWhenCaseNotPresent() {
    UUID unknownId = UUID.randomUUID();
    registry.indexWorkerForCompletion(unknownId, "worker-a", "plan-item-1");
    // no entry created as a side-effect
    assertThat(registry.get(unknownId)).isEmpty();
    assertThat(registry.getPlanItemId(unknownId, "worker-a")).isEmpty();
  }
}
