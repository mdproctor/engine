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
package io.casehub.engine.internal.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.DispatchBudget;
import io.casehub.api.spi.DispatchBudgetQuery;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.PlanItemStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseContextChangedEventHandlerConcurrencyTest {

  @Test
  void applyDispatchBudget_caseCap_limits_dispatch_count() {
    var handler = new CaseContextChangedEventHandler();
    var store = mock(PlanItemStore.class);
    var budget = mock(DispatchBudget.class);

    injectField(handler, "planItemStore", store);
    injectField(handler, "dispatchBudget", budget);

    UUID caseId = UUID.randomUUID();
    var instance = stubInstance(caseId, "tenant-1");
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("throttled")
            .version("1.0")
            .maxConcurrentDispatches(3)
            .build();

    when(store.findByCaseId(caseId, "tenant-1"))
        .thenReturn(
            List.of(planItem(caseId, TaskStatus.RUNNING), planItem(caseId, TaskStatus.RUNNING)));
    when(budget.availableCapacity(any(DispatchBudgetQuery.class))).thenReturn(Integer.MAX_VALUE);

    List<Binding> selected = bindings(5);

    var result = invokeApplyDispatchBudget(handler, instance, definition, selected);

    assertThat(result).hasSize(1);
  }

  @Test
  void applyDispatchBudget_allSlotsOccupied_returnsEmpty() {
    var handler = new CaseContextChangedEventHandler();
    var store = mock(PlanItemStore.class);
    var budget = mock(DispatchBudget.class);

    injectField(handler, "planItemStore", store);
    injectField(handler, "dispatchBudget", budget);

    UUID caseId = UUID.randomUUID();
    var instance = stubInstance(caseId, "tenant-1");
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("throttled")
            .version("1.0")
            .maxConcurrentDispatches(2)
            .build();

    when(store.findByCaseId(caseId, "tenant-1"))
        .thenReturn(
            List.of(
                planItem(caseId, TaskStatus.RUNNING), planItem(caseId, TaskStatus.DISPATCHING)));
    when(budget.availableCapacity(any(DispatchBudgetQuery.class))).thenReturn(Integer.MAX_VALUE);

    var result = invokeApplyDispatchBudget(handler, instance, definition, bindings(3));

    assertThat(result).isEmpty();
  }

  @Test
  void applyDispatchBudget_externalBudget_wins_when_lower() {
    var handler = new CaseContextChangedEventHandler();
    var store = mock(PlanItemStore.class);
    var budget = mock(DispatchBudget.class);

    injectField(handler, "planItemStore", store);
    injectField(handler, "dispatchBudget", budget);

    UUID caseId = UUID.randomUUID();
    var instance = stubInstance(caseId, "tenant-1");
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("throttled")
            .version("1.0")
            .maxConcurrentDispatches(10)
            .build();

    when(store.findByCaseId(caseId, "tenant-1")).thenReturn(List.of());
    when(budget.availableCapacity(any(DispatchBudgetQuery.class))).thenReturn(2);

    var result = invokeApplyDispatchBudget(handler, instance, definition, bindings(5));

    assertThat(result).hasSize(2);
  }

  @Test
  void applyDispatchBudget_nullMaxConcurrent_unlimited() {
    var handler = new CaseContextChangedEventHandler();
    var store = mock(PlanItemStore.class);
    var budget = mock(DispatchBudget.class);

    injectField(handler, "planItemStore", store);
    injectField(handler, "dispatchBudget", budget);

    UUID caseId = UUID.randomUUID();
    var instance = stubInstance(caseId, "tenant-1");
    var definition =
        CaseDefinition.builder().namespace("test").name("unlimited").version("1.0").build();

    when(budget.availableCapacity(any(DispatchBudgetQuery.class))).thenReturn(Integer.MAX_VALUE);

    var result = invokeApplyDispatchBudget(handler, instance, definition, bindings(10));

    assertThat(result).hasSize(10);
  }

  @Test
  void applyDispatchBudget_pendingPlanItems_notCounted() {
    var handler = new CaseContextChangedEventHandler();
    var store = mock(PlanItemStore.class);
    var budget = mock(DispatchBudget.class);

    injectField(handler, "planItemStore", store);
    injectField(handler, "dispatchBudget", budget);

    UUID caseId = UUID.randomUUID();
    var instance = stubInstance(caseId, "tenant-1");
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("throttled")
            .version("1.0")
            .maxConcurrentDispatches(3)
            .build();

    when(store.findByCaseId(caseId, "tenant-1"))
        .thenReturn(
            List.of(
                planItem(caseId, TaskStatus.PENDING),
                planItem(caseId, TaskStatus.COMPLETED),
                planItem(caseId, TaskStatus.RUNNING)));
    when(budget.availableCapacity(any(DispatchBudgetQuery.class))).thenReturn(Integer.MAX_VALUE);

    var result = invokeApplyDispatchBudget(handler, instance, definition, bindings(5));

    assertThat(result).hasSize(2);
  }

  @Test
  void applyDispatchBudget_emptySelected_returnsEmpty() {
    var handler = new CaseContextChangedEventHandler();
    var store = mock(PlanItemStore.class);
    var budget = mock(DispatchBudget.class);

    injectField(handler, "planItemStore", store);
    injectField(handler, "dispatchBudget", budget);

    UUID caseId = UUID.randomUUID();
    var instance = stubInstance(caseId, "tenant-1");
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("throttled")
            .version("1.0")
            .maxConcurrentDispatches(5)
            .build();

    var result = invokeApplyDispatchBudget(handler, instance, definition, List.of());

    assertThat(result).isEmpty();
  }

  // --- helpers ---

  private static CaseInstance stubInstance(UUID caseId, String tenancyId) {
    var instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    instance.tenancyId = tenancyId;
    return instance;
  }

  private static PlanItemRecord planItem(UUID caseId, TaskStatus status) {
    return PlanItemRecord.primitive(
        caseId,
        UUID.randomUUID().toString(),
        "binding-" + status,
        status,
        Instant.now(),
        io.casehub.engine.common.internal.model.TargetType.CAPABILITY,
        ".",
        "tenant-1",
        null,
        "worker-1",
        null);
  }

  private static List<Binding> bindings(int count) {
    List<Binding> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      var b = mock(Binding.class);
      when(b.getName()).thenReturn("binding-" + i);
      result.add(b);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<Binding> invokeApplyDispatchBudget(
      CaseContextChangedEventHandler handler,
      CaseInstance instance,
      CaseDefinition definition,
      List<Binding> selected) {
    try {
      var method =
          CaseContextChangedEventHandler.class.getDeclaredMethod(
              "applyDispatchBudget", CaseInstance.class, CaseDefinition.class, List.class);
      method.setAccessible(true);
      return (List<Binding>) method.invoke(handler, instance, definition, selected);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void injectField(Object target, String fieldName, Object value) {
    try {
      var field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
