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
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies that CaseInstance transitions through the correct {@link CaseStatus} states and that the
 * EventLog records the right event types at each transition.
 */
@QuarkusTest
class CaseLifecycleStateTest {

  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject IdleCaseHubBean idleBean;

  @Inject CompletingCaseHubBean completingBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject ReactiveCaseInstanceRepository reactiveCaseInstanceRepository;

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  // ------------------------------------------------------------------ //
  // RUNNING state                                                         //
  // ------------------------------------------------------------------ //

  @Test
  void caseStartsInRunningState() {
    // A case whose binding trigger does not match the initial context never fires a worker,
    // so the case stays in RUNNING. This lets us assert RUNNING without racing against
    // an asynchronous completion.
    UUID caseId =
        idleBean
            .startCase(Map.of("status", "idle")) // trigger requires "active" — never fires
            .toCompletableFuture()
            .join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.RUNNING);
            });
  }

  @Test
  void runningStateIsPersistedByRepository() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(caseInstanceCache.get(caseId)).isNotNull());

    // Repository must store RUNNING — not the legacy ACTIVE value.
    var stored =
        reactiveCaseInstanceRepository
            .findByUuid(caseId, TenancyConstants.DEFAULT_TENANT_ID)
            .await()
            .atMost(SPI_TIMEOUT);

    assertThat(stored).as("CaseInstance must be findable by UUID after start").isNotNull();
    assertThat(stored.getState())
        .as("Repository must store RUNNING, not legacy ACTIVE")
        .isEqualTo(CaseStatus.RUNNING);
  }

  // ------------------------------------------------------------------ //
  // COMPLETED state                                                       //
  // ------------------------------------------------------------------ //

  @Test
  void caseTransitionsToCompletedWhenSuccessGoalReached() {
    UUID caseId =
        completingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });
  }

  @Test
  void completedCaseHasCaseCompletedInEventLog() {
    UUID caseId =
        completingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<EventLog> events = findEvents(caseId, CaseHubEventType.CASE_COMPLETED);
              assertThat(events)
                  .as("EventLog must contain exactly one CASE_COMPLETED entry")
                  .hasSize(1);
            });
  }

  @Test
  void completedCaseDoesNotHaveLegacyCaseFailedInEventLog() {
    // Guard: CASE_FAILED must never appear — the correct name is CASE_FAULTED.
    UUID caseId =
        completingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .isEqualTo(CaseStatus.COMPLETED));

    List<EventLog> legacyEvents = findEventsByTypeName(caseId, "CASE_FAILED");
    assertThat(legacyEvents)
        .as("CASE_FAILED must not appear in EventLog — correct name is CASE_FAULTED")
        .isEmpty();
  }

  // ------------------------------------------------------------------ //
  // Helpers                                                               //
  // ------------------------------------------------------------------ //

  private List<EventLog> findEvents(UUID caseId, CaseHubEventType eventType) {
    return reactiveEventLogRepository
        .findByCaseAndTypes(caseId, List.of(eventType), TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT);
  }

  private List<EventLog> findEventsByTypeName(UUID caseId, String eventTypeName) {
    return reactiveEventLogRepository
        .findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.values()), TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT)
        .stream()
        .filter(e -> eventTypeName.equals(e.getEventType().name()))
        .toList();
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                            //
  // ------------------------------------------------------------------ //

  /** Case whose binding trigger never fires — stays RUNNING indefinitely. */
  @ApplicationScoped
  public static class IdleCaseHubBean extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("idleCapability")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-lifecycle-state")
          .name("Idle Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("idle-worker")
                  .capabilityName("idleCapability")
                  .function(
                      new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("status", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-when-active")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"active\"")) // never matches "idle"
                  .build())
          .build();
    }
  }

  /** Case that completes via a success goal. */
  @ApplicationScoped
  public static class CompletingCaseHubBean extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("processDocumentLifecycle")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal successGoal =
        Goal.builder()
            .name("processingComplete")
            .condition(".status == \"done\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-lifecycle-state")
          .name("Completing Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("completing-worker")
                  .capabilityName("processDocumentLifecycle")
                  .function(
                      new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("status", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-processing")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"processing\""))
                  .build())
          .goals(successGoal)
          .completion(GoalExpression.allOf(successGoal))
          .build();
    }
  }
}
