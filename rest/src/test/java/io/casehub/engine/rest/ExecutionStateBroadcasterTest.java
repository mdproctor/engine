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
package io.casehub.engine.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.spi.event.CaseContextUpdatedEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.rest.dto.ExecutionStateSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ExecutionStateBroadcasterTest {

  @Inject ExecutionStateBroadcaster broadcaster;

  @Test
  void planItemEventTriggersSnapshot() {
    UUID caseId = UUID.randomUUID();

    var subscription =
        broadcaster.stream(caseId)
            .select()
            .first(1)
            .collect()
            .asList()
            .subscribeAsCompletionStage();

    broadcaster.onPlanItemChanged(
        new PlanItemStateChangedEvent(
            caseId, "pi-1", "analysis", TaskStatus.PENDING, TaskStatus.RUNNING, "t1"));

    List<ExecutionStateSnapshot> result = subscription.toCompletableFuture().join();
    assertEquals(1, result.size());
    assertNotNull(result.get(0).executionId());
    assertEquals(caseId.toString(), result.get(0).executionId());
  }

  @Test
  void contextEventTriggersSnapshot() {
    UUID caseId = UUID.randomUUID();

    var subscription =
        broadcaster.stream(caseId)
            .select()
            .first(1)
            .collect()
            .asList()
            .subscribeAsCompletionStage();

    broadcaster.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", "t1"));

    List<ExecutionStateSnapshot> result = subscription.toCompletableFuture().join();
    assertEquals(1, result.size());
  }

  @Test
  void filtersByCaseId() {
    UUID targetCase = UUID.randomUUID();
    UUID otherCase = UUID.randomUUID();

    var subscription =
        broadcaster.stream(targetCase)
            .select()
            .first(1)
            .collect()
            .asList()
            .subscribeAsCompletionStage();

    broadcaster.onPlanItemChanged(
        new PlanItemStateChangedEvent(
            otherCase, "pi-other", "review", TaskStatus.PENDING, TaskStatus.RUNNING, "t1"));

    broadcaster.onPlanItemChanged(
        new PlanItemStateChangedEvent(
            targetCase, "pi-1", "analysis", TaskStatus.RUNNING, TaskStatus.COMPLETED, "t1"));

    List<ExecutionStateSnapshot> result = subscription.toCompletableFuture().join();
    assertEquals(1, result.size());
    assertEquals(targetCase.toString(), result.get(0).executionId());
  }
}
