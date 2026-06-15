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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that CaseOutcomeObserver.onOutcome() is called when a case reaches a terminal state.
 *
 * <p>Injects the handler directly and calls the @ConsumeEvent method to avoid the in-memory
 * repository lock contention that occurs in the full async lifecycle path. The production JPA path
 * does not have this issue. Refs casehubio/engine#477.
 */
@QuarkusTest
class CaseOutcomeObserverTest {

  @Inject CaseStatusChangedHandler statusChangedHandler;
  @Inject OutcomeCapturingObserver captureObserver;

  @BeforeEach
  void reset() {
    OutcomeCapturingObserver.capturedEvents.clear();
  }

  @Test
  void caseOutcomeObserver_called_when_case_completes() {
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("test-outcome-case");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl(Map.of("result", "done")));
    instance.tenancyId = "test-tenant";

    statusChangedHandler
        .onCaseStatusChangedHandler(new CaseStatusChanged(instance, "RUNNING", "COMPLETED"))
        .await()
        .indefinitely();

    assertThat(OutcomeCapturingObserver.capturedEvents)
        .as("CaseOutcomeObserver.onOutcome() must be called when case COMPLETES — engine#477")
        .hasSize(1);

    CaseOutcomeEvent event = OutcomeCapturingObserver.capturedEvents.get(0);
    assertThat(event.caseId()).isEqualTo(instance.getUuid());
    assertThat(event.outcomeLabel()).isEqualTo("COMPLETED");
    assertThat(event.caseType()).isEqualTo("test-outcome-case");
    assertThat(event.caseFileSnapshot()).containsEntry("result", "done");
    assertThat(event.closedAt()).isNotNull();
  }

  @Test
  void caseOutcomeObserver_called_when_case_faults() {
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("test-fault-case");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl(Map.of("error", "timeout")));
    instance.tenancyId = "test-tenant";

    statusChangedHandler
        .onCaseStatusChangedHandler(new CaseStatusChanged(instance, "RUNNING", "FAULTED"))
        .await()
        .indefinitely();

    assertThat(OutcomeCapturingObserver.capturedEvents).hasSize(1);
    assertThat(OutcomeCapturingObserver.capturedEvents.get(0).outcomeLabel()).isEqualTo("FAULTED");
  }

  @Test
  void caseOutcomeObserver_not_called_for_non_terminal_state() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseContext(new CaseContextImpl());
    instance.tenancyId = "test-tenant";

    statusChangedHandler
        .onCaseStatusChangedHandler(new CaseStatusChanged(instance, "RUNNING", "SUSPENDED"))
        .await()
        .indefinitely();

    assertThat(OutcomeCapturingObserver.capturedEvents)
        .as("CaseOutcomeObserver must NOT be called for non-terminal state transitions")
        .isEmpty();
  }

  @ApplicationScoped
  static class OutcomeCapturingObserver implements CaseOutcomeObserver {
    static final CopyOnWriteArrayList<CaseOutcomeEvent> capturedEvents =
        new CopyOnWriteArrayList<>();

    @Override
    public void onOutcome(CaseOutcomeEvent event) {
      capturedEvents.add(event);
    }
  }
}
