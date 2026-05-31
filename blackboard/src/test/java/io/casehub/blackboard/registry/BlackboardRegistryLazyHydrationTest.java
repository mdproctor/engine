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

import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.persistence.memory.MemoryPlanItemStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BlackboardRegistryLazyHydrationTest {

  @Inject BlackboardRegistry registry;
  @Inject PlanItemStore planItemStore;

  @BeforeEach
  void setUp() {
    if (planItemStore instanceof MemoryPlanItemStore mem) {
      mem.clear();
    }
  }

  @Test
  void get_returnsEmptyWhenStoreHasNoRecordsForCase() {
    UUID caseId = UUID.randomUUID();
    assertThat(registry.get(caseId)).isEmpty();
  }

  @Test
  void get_hydratesDelegatedPlanItemFromStore() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();

    planItemStore.save(
        new PlanItemSaveRequest(
            caseId,
            planItemId,
            "review-binding",
            PlanItemStatus.DELEGATED,
            Instant.now(),
            TargetType.HUMAN_TASK,
            ".result.decision",
            "test-tenant"),
        "test-tenant");

    Optional<CasePlanModel> result = registry.get(caseId);

    assertThat(result).isPresent();
    assertThat(result.get().getPlanItem(planItemId)).isPresent();
    PlanItem item = result.get().getPlanItem(planItemId).get();
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
    assertThat(item.getBindingName()).isEqualTo("review-binding");
    assertThat(item.getTarget()).isInstanceOf(HumanTaskTarget.class);
    HumanTaskTarget ht = (HumanTaskTarget) item.getTarget();
    assertThat(ht.outputMapping()).isNotNull();
    assertThat(ht.outputMapping()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) ht.outputMapping()).expression())
        .isEqualTo(".result.decision");
  }

  @Test
  void get_hydratesDelegatedPlanItemWithNullExpression() {
    UUID caseId = UUID.randomUUID();
    String planItemId = UUID.randomUUID().toString();

    planItemStore.save(
        new PlanItemSaveRequest(
            caseId,
            planItemId,
            "approve-binding",
            PlanItemStatus.DELEGATED,
            Instant.now(),
            TargetType.HUMAN_TASK,
            null,
            "test-tenant"),
        "test-tenant");

    Optional<CasePlanModel> result = registry.get(caseId);

    assertThat(result).isPresent();
    PlanItem item = result.get().getPlanItem(planItemId).get();
    assertThat(item.getTarget()).isInstanceOf(HumanTaskTarget.class);
    HumanTaskTarget ht = (HumanTaskTarget) item.getTarget();
    assertThat(ht.outputMapping()).isNull();
  }

  @Test
  void get_onlyHydratesDelegatedNotPendingOrCompleted() {
    UUID caseId = UUID.randomUUID();

    planItemStore.save(
        new PlanItemSaveRequest(
            caseId,
            UUID.randomUUID().toString(),
            "pending-binding",
            PlanItemStatus.PENDING,
            Instant.now(),
            null,
            null,
            "test-tenant"),
        "test-tenant");
    planItemStore.save(
        new PlanItemSaveRequest(
            caseId,
            UUID.randomUUID().toString(),
            "completed-binding",
            PlanItemStatus.COMPLETED,
            Instant.now(),
            TargetType.HUMAN_TASK,
            null,
            "test-tenant"),
        "test-tenant");

    Optional<CasePlanModel> result = registry.get(caseId);

    // Only DELEGATED items hydrate; PENDING and COMPLETED don't count
    assertThat(result).isEmpty();
  }
}
