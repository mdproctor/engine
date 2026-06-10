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
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutionPolicy;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.RetryPolicy;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a {@link CaseStatus#FAULTED} transition occurs correctly — both via exhausted
 * worker retries and via a failure goal — and that the EventLog records {@code CASE_FAULTED} (not
 * the pre-alignment {@code CASE_FAILED}).
 */
@QuarkusTest
class CaseFaultedStateTest {

  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject AlwaysFailingCaseHubBean alwaysFailingBean;

  @Inject FailureGoalCaseHubBean failureGoalBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject EventLogRepository eventLogRepository;

  @BeforeEach
  void reset() {
    AlwaysFailingCaseHubBean.runCount.set(0);
  }

  // ------------------------------------------------------------------ //
  // FAULTED via exhausted retries                                         //
  // ------------------------------------------------------------------ //

  @Test
  void caseTransitionsToFaultedWhenAllRetriesExhausted() {
    UUID caseId =
        alwaysFailingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState())
                  .as("Case must be FAULTED after all retries exhausted")
                  .isEqualTo(CaseStatus.FAULTED);
            });
  }

  @Test
  void faultedCaseHasCaseFaultedInEventLog() {
    // The event type must be CASE_FAULTED — not the legacy CASE_FAILED.
    UUID caseId =
        alwaysFailingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    // WorkerRetriesExhaustedEventHandler writes one CASE_FAULTED entry directly;
    // CaseStatusChangedHandler writes a second one via the CASE_STATUS_CHANGED event.
    // Assert at least one exists — the important thing is the correct event type is recorded.
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(findEvents(caseId, CaseHubEventType.CASE_FAULTED))
                    .as("EventLog must contain at least one CASE_FAULTED entry")
                    .isNotEmpty());
  }

  @Test
  void faultedCaseHasNoLegacyCaseFailedInEventLog() {
    UUID caseId =
        alwaysFailingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState()).isEqualTo(CaseStatus.FAULTED));

    assertThat(findEventsByTypeName(caseId, "CASE_FAILED"))
        .as("CASE_FAILED must never appear — correct name is CASE_FAULTED")
        .isEmpty();
  }

  @Test
  void allRetriesAreAttemptedBeforeFaulting() {
    // RetryPolicy(2, 200) = 2 max attempts. Worker must be called exactly twice.
    UUID caseId =
        alwaysFailingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState()).isEqualTo(CaseStatus.FAULTED));

    assertThat(AlwaysFailingCaseHubBean.runCount.get())
        .as("Worker must be attempted exactly maxAttempts times before FAULTED")
        .isEqualTo(2);
  }

  // ------------------------------------------------------------------ //
  // FAULTED via failure goal                                              //
  // ------------------------------------------------------------------ //

  @Test
  void caseTransitionsToFaultedWhenFailureGoalReached() {
    // Worker writes {status: "error"}, which satisfies the failure goal condition.
    // GoalReachedEventHandler then drives the FAULTED transition.
    UUID caseId =
        failureGoalBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState())
                  .as("Case must be FAULTED when failure goal is reached")
                  .isEqualTo(CaseStatus.FAULTED);
            });
  }

  @Test
  void failureGoalCaseHasCaseFaultedInEventLog() {
    UUID caseId =
        failureGoalBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(findEvents(caseId, CaseHubEventType.CASE_FAULTED))
                    .as("Failure goal must produce exactly one CASE_FAULTED EventLog entry")
                    .hasSize(1));
  }

  // ------------------------------------------------------------------ //
  // Helpers                                                               //
  // ------------------------------------------------------------------ //

  private List<EventLog> findEvents(UUID caseId, CaseHubEventType eventType) {
    return eventLogRepository
        .findByCaseAndTypes(caseId, List.of(eventType), TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT);
  }

  private List<EventLog> findEventsByTypeName(UUID caseId, String eventTypeName) {
    return eventLogRepository
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

  /** Worker that always throws — retries exhaust and the case becomes FAULTED. */
  @ApplicationScoped
  public static class AlwaysFailingCaseHubBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger(0);

    private final Capability capability =
        Capability.builder()
            .name("alwaysFailCapability")
            .inputSchema("{ status: .working.status }")
            .outputSchema("{ status: .status }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-faulted-state")
          .name("Always Failing Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("always-failing-worker")
                  .capabilities(capability)
                  .function(
                      input -> {
                        runCount.incrementAndGet();
                        throw new RuntimeException("Simulated permanent failure");
                      })
                  // 2 max attempts, 200 ms between retries — fast but realistic
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(2, 200)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-processing")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".working.status == \"processing\""))
                  .build())
          .build();
    }
  }

  /**
   * Worker writes {@code {status: "error"}}, satisfying the failure goal — case becomes FAULTED via
   * {@code GoalReachedEventHandler}, not via retry exhaustion.
   */
  @ApplicationScoped
  public static class FailureGoalCaseHubBean extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("failureGoalCapability")
            .inputSchema("{ status: .working.status }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal failureGoal =
        Goal.builder()
            .name("processingFailed")
            .condition(".working.status == \"error\"")
            .kind(GoalKind.FAILURE)
            .build();

    private final Goal successGoal =
        Goal.builder()
            .name("processingSucceeded")
            .condition(".working.status == \"ok\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-faulted-state")
          .name("Failure Goal Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("error-producing-worker")
                  .capabilities(capability)
                  .function(
                      input -> WorkerResult.of(Map.of("status", "error"))) // satisfies failure goal
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-processing")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".working.status == \"processing\""))
                  .build())
          .goals(failureGoal, successGoal)
          .completion(GoalExpression.allOf(successGoal), GoalExpression.allOf(failureGoal))
          .build();
    }
  }
}
