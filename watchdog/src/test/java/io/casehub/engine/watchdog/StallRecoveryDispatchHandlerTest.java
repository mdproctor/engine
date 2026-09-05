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
package io.casehub.engine.watchdog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.StallRecoveryContext;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StallRecoveryDispatchHandlerTest {

  private StallRecoveryDispatchHandler handler;
  private PlanItemStore planItemStore;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    handler = new StallRecoveryDispatchHandler();
    planItemStore = mock(PlanItemStore.class);
    Instance<PlanItemStore> instance = mock(Instance.class);
    when(instance.isResolvable()).thenReturn(true);
    when(instance.get()).thenReturn(planItemStore);
    handler.planItemStore = instance;
  }

  @Test
  void resolvesBindingByExecutorNameMatch() {
    UUID caseId = UUID.randomUUID();
    String tenancyId = "tenant-1";
    Instant now = Instant.now();

    PlanItemRecord match =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "aml-review",
            TaskStatus.RUNNING,
            now,
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);

    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(match));

    StallRecoveryContext ctx =
        new StallRecoveryContext(
            caseId,
            tenancyId,
            WatchdogConditionType.LOOP_DETECTED,
            List.of("agent-1"),
            "loop",
            null,
            now,
            null,
            null);

    StallRecoveryContext resolved = handler.resolveBinding(ctx);
    assertEquals("aml-review", resolved.resolvedBindingName());
    assertEquals("pi-1", resolved.resolvedPlanItemId());
  }

  @Test
  void selectsMostRecentWhenMultipleMatch() {
    UUID caseId = UUID.randomUUID();
    String tenancyId = "tenant-1";
    Instant earlier = Instant.now().minusSeconds(60);
    Instant later = Instant.now();

    PlanItemRecord old =
        PlanItemRecord.primitive(
            caseId,
            "pi-old",
            "binding-old",
            TaskStatus.RUNNING,
            earlier,
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);
    PlanItemRecord recent =
        PlanItemRecord.primitive(
            caseId,
            "pi-new",
            "binding-new",
            TaskStatus.RUNNING,
            later,
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);

    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(old, recent));

    StallRecoveryContext ctx =
        new StallRecoveryContext(
            caseId,
            tenancyId,
            WatchdogConditionType.ECHO_CHAMBER,
            List.of("agent-1"),
            "echo",
            null,
            later,
            null,
            null);

    StallRecoveryContext resolved = handler.resolveBinding(ctx);
    assertEquals("binding-new", resolved.resolvedBindingName());
    assertEquals("pi-new", resolved.resolvedPlanItemId());
  }

  @Test
  void returnsUnresolvedWhenNoRunningMatch() {
    UUID caseId = UUID.randomUUID();
    String tenancyId = "tenant-1";
    Instant now = Instant.now();

    PlanItemRecord completed =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "binding-1",
            TaskStatus.COMPLETED,
            now,
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);

    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(completed));

    StallRecoveryContext ctx =
        new StallRecoveryContext(
            caseId,
            tenancyId,
            WatchdogConditionType.LOOP_DETECTED,
            List.of("agent-1"),
            "loop",
            null,
            now,
            null,
            null);

    StallRecoveryContext resolved = handler.resolveBinding(ctx);
    assertNull(resolved.resolvedBindingName());
    assertNull(resolved.resolvedPlanItemId());
  }

  @Test
  void returnsUnresolvedWhenNoAgentIds() {
    UUID caseId = UUID.randomUUID();
    StallRecoveryContext ctx =
        new StallRecoveryContext(
            caseId,
            "tenant-1",
            WatchdogConditionType.BARRIER_STUCK,
            List.of(),
            "stuck",
            null,
            Instant.now(),
            null,
            null);

    StallRecoveryContext resolved = handler.resolveBinding(ctx);
    assertNull(resolved.resolvedBindingName());
  }

  @Test
  void returnsUnresolvedWhenExecutorNameDoesNotMatch() {
    UUID caseId = UUID.randomUUID();
    String tenancyId = "tenant-1";
    Instant now = Instant.now();

    PlanItemRecord running =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "binding-1",
            TaskStatus.RUNNING,
            now,
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-2",
            null);

    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(running));

    StallRecoveryContext ctx =
        new StallRecoveryContext(
            caseId,
            tenancyId,
            WatchdogConditionType.LOOP_DETECTED,
            List.of("agent-1"),
            "loop",
            null,
            now,
            null,
            null);

    StallRecoveryContext resolved = handler.resolveBinding(ctx);
    assertNull(resolved.resolvedBindingName());
  }

  @SuppressWarnings("unchecked")
  @Test
  void returnsUnresolvedWhenPlanItemStoreNotAvailable() {
    Instance<PlanItemStore> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    handler.planItemStore = absent;

    StallRecoveryContext ctx =
        new StallRecoveryContext(
            UUID.randomUUID(),
            "tenant-1",
            WatchdogConditionType.LOOP_DETECTED,
            List.of("agent-1"),
            "loop",
            null,
            Instant.now(),
            null,
            null);

    StallRecoveryContext resolved = handler.resolveBinding(ctx);
    assertNull(resolved.resolvedBindingName());
  }
}
