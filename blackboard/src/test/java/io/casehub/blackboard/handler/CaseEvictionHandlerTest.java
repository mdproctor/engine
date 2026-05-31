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
package io.casehub.blackboard.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseStatus;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for CaseEvictionHandler. See casehubio/engine#76. */
class CaseEvictionHandlerTest {

  @Test
  void evicts_plan_model_on_completed_status() {
    BlackboardRegistry registry = new BlackboardRegistry();
    UUID caseId = UUID.randomUUID();
    registry.getOrCreate(caseId, "test-tenant");

    CaseEvictionHandler handler = new CaseEvictionHandler(registry);
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);

    handler
        .onCaseStatusChanged(
            new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.COMPLETED.name()))
        .await()
        .indefinitely();

    assertThat(registry.get(caseId)).isEmpty();
  }

  @Test
  void evicts_on_faulted_status() {
    BlackboardRegistry registry = new BlackboardRegistry();
    UUID caseId = UUID.randomUUID();
    registry.getOrCreate(caseId, "test-tenant");
    CaseEvictionHandler handler = new CaseEvictionHandler(registry);
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);

    handler
        .onCaseStatusChanged(
            new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.FAULTED.name()))
        .await()
        .indefinitely();

    assertThat(registry.get(caseId)).isEmpty();
  }

  @Test
  void evicts_on_cancelled_status() {
    BlackboardRegistry registry = new BlackboardRegistry();
    UUID caseId = UUID.randomUUID();
    registry.getOrCreate(caseId, "test-tenant");
    CaseEvictionHandler handler = new CaseEvictionHandler(registry);
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);

    handler
        .onCaseStatusChanged(
            new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.CANCELLED.name()))
        .await()
        .indefinitely();

    assertThat(registry.get(caseId)).isEmpty();
  }

  @Test
  void does_not_evict_on_running_status() {
    BlackboardRegistry registry = new BlackboardRegistry();
    UUID caseId = UUID.randomUUID();
    registry.getOrCreate(caseId, "test-tenant");
    CaseEvictionHandler handler = new CaseEvictionHandler(registry);
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);

    handler
        .onCaseStatusChanged(
            new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.RUNNING.name()))
        .await()
        .indefinitely();

    assertThat(registry.get(caseId)).isPresent();
  }

  @Test
  void no_plan_model_does_not_throw() {
    BlackboardRegistry registry = new BlackboardRegistry();
    CaseEvictionHandler handler = new CaseEvictionHandler(registry);
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(UUID.randomUUID());

    handler
        .onCaseStatusChanged(
            new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.COMPLETED.name()))
        .await()
        .indefinitely();
    // no exception expected
  }
}
