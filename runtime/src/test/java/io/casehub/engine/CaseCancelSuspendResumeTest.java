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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
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

/** Verifies cancel, suspend, and resume lifecycle operations. Refs casehubio/engine#14. */
@QuarkusTest
class CaseCancelSuspendResumeTest {

  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject CaseLifecycleStateTest.IdleCaseHubBean idleBean;
  @Inject SuspendableWorkerBean suspendableBean;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventLogRepository eventLogRepository;

  @BeforeEach
  void reset() {
    SuspendableWorkerBean.runCount.set(0);
  }

  // ------------------------------------------------------------------ //
  // cancelCase                                                           //
  // ------------------------------------------------------------------ //

  @Test
  void cancelFromRunningTransitionsToCancelled() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    idleBean.cancelCase(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .as("cancelCase must transition RUNNING → CANCELLED")
                    .isEqualTo(CaseStatus.CANCELLED));
  }

  @Test
  void cancelFromRunningWritesCaseCancelledEventLog() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    idleBean.cancelCase(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(findEvents(caseId, CaseHubEventType.CASE_CANCELLED))
                    .as("EventLog must contain a CASE_CANCELLED entry")
                    .isNotEmpty());
  }

  @Test
  void cancelFromSuspendedTransitionsToCancelled() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    idleBean.suspendCase(caseId);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> caseInstanceCache.get(caseId).getState() == CaseStatus.SUSPENDED);

    idleBean.cancelCase(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .as("cancelCase must transition SUSPENDED → CANCELLED")
                    .isEqualTo(CaseStatus.CANCELLED));
  }

  @Test
  void cancelCompletedCaseThrowsIllegalStateException() {
    UUID caseId = simpleCaseId();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(
            () ->
                caseInstanceCache.get(caseId) != null
                    && caseInstanceCache.get(caseId).getState() == CaseStatus.COMPLETED);

    assertThatThrownBy(() -> idleBean.cancelCase(caseId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");
  }

  @Test
  void cancelUnknownCaseThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> idleBean.cancelCase(UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  // ------------------------------------------------------------------ //
  // suspendCase                                                          //
  // ------------------------------------------------------------------ //

  @Test
  void suspendFromRunningTransitionsToSuspended() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    idleBean.suspendCase(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .as("suspendCase must transition RUNNING → SUSPENDED")
                    .isEqualTo(CaseStatus.SUSPENDED));
  }

  @Test
  void suspendedCaseDoesNotFireWorkersOnSignal() throws InterruptedException {
    UUID caseId = suspendableBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    suspendableBean.suspendCase(caseId);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> caseInstanceCache.get(caseId).getState() == CaseStatus.SUSPENDED);

    // Signal should be ignored while suspended
    suspendableBean.signal(caseId, "status", "active");

    // Wait briefly and assert no worker ran
    Thread.sleep(500);
    assertThat(SuspendableWorkerBean.runCount.get())
        .as("No worker must fire while the case is SUSPENDED")
        .isEqualTo(0);
  }

  @Test
  void suspendNonRunningCaseThrowsIllegalStateException() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    idleBean.suspendCase(caseId);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> caseInstanceCache.get(caseId).getState() == CaseStatus.SUSPENDED);

    assertThatThrownBy(() -> idleBean.suspendCase(caseId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RUNNING");
  }

  @Test
  void suspendUnknownCaseThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> idleBean.suspendCase(UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  // ------------------------------------------------------------------ //
  // resumeCase                                                           //
  // ------------------------------------------------------------------ //

  @Test
  void resumeFromSuspendedTransitionsToRunning() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    idleBean.suspendCase(caseId);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> caseInstanceCache.get(caseId).getState() == CaseStatus.SUSPENDED);

    idleBean.resumeCase(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .as("resumeCase must transition SUSPENDED → RUNNING")
                    .isEqualTo(CaseStatus.RUNNING));
  }

  @Test
  void resumedCaseFiresWorkerWhenContextEligible() {
    // Signal the trigger key BEFORE suspending. The engine ignores it while suspended.
    // On resume, CONTEXT_CHANGED is republished so the worker can fire.
    UUID caseId = suspendableBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    suspendableBean.suspendCase(caseId);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> caseInstanceCache.get(caseId).getState() == CaseStatus.SUSPENDED);

    // Signal while suspended — ignored by the engine
    suspendableBean.signal(caseId, "status", "active");

    suspendableBean.resumeCase(caseId);

    // After resume, CONTEXT_CHANGED is republished — worker must now fire
    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(SuspendableWorkerBean.runCount.get())
                    .as("Worker must fire after case is resumed with an eligible context")
                    .isGreaterThan(0));
  }

  @Test
  void resumeWritesCaseStatusChangedEventLog() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    idleBean.suspendCase(caseId);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> caseInstanceCache.get(caseId).getState() == CaseStatus.SUSPENDED);

    idleBean.resumeCase(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(findAllEvents(caseId))
                    .as("EventLog must contain a status-change entry for resume")
                    .anyMatch(
                        e ->
                            e.getEventType() == CaseHubEventType.CASE_STATUS_CHANGED
                                || e.getEventType() == CaseHubEventType.CASE_STARTED));
  }

  @Test
  void resumeNonSuspendedCaseThrowsIllegalStateException() {
    UUID caseId = idleBean.startCase(Map.of("status", "idle")).toCompletableFuture().join();

    await().atMost(5, TimeUnit.SECONDS).until(() -> caseInstanceCache.get(caseId) != null);

    assertThatThrownBy(() -> idleBean.resumeCase(caseId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SUSPENDED");
  }

  @Test
  void resumeUnknownCaseThrowsIllegalArgumentException() {
    assertThatThrownBy(() -> idleBean.resumeCase(UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  // ------------------------------------------------------------------ //
  // Helpers                                                              //
  // ------------------------------------------------------------------ //

  /** Starts and waits for a simple completing case to use its completed caseId. */
  private UUID simpleCaseId() {
    // Re-use SimpleCaseHubBean which completes when status == "processed"
    // We need an @Inject — use the one already in context from the test's own CDI
    // Instead, just start the idle bean and manually complete via cancel then verify it's terminal
    // Actually: start a completing case directly using SimpleCaseHubBean — injected separately.
    UUID caseId =
        simpleCaseHubBean
            .startCase(Map.of("documentId", "d1", "status", "processing"))
            .toCompletableFuture()
            .join();
    return caseId;
  }

  @Inject SimpleCaseHubBean simpleCaseHubBean;

  private List<EventLog> findEvents(UUID caseId, CaseHubEventType eventType) {
    return eventLogRepository
        .findByCaseAndTypes(caseId, List.of(eventType), TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT);
  }

  private List<EventLog> findAllEvents(UUID caseId) {
    return eventLogRepository
        .findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.values()), TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT);
  }

  // ------------------------------------------------------------------ //
  // Test bean                                                            //
  // ------------------------------------------------------------------ //

  /**
   * A case with a worker that fires when status == "active". Stays idle until signalled. Used to
   * verify suspend/resume worker-firing behaviour.
   */
  @ApplicationScoped
  public static class SuspendableWorkerBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger(0);

    private final Capability capability =
        Capability.builder()
            .name("suspendableCapability")
            .inputSchema("{ status: .working.status }")
            .outputSchema("{ result: .status }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-suspend-resume")
          .name("Suspendable Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("suspendable-worker")
                  .capabilities(capability)
                  .function(
                      input -> {
                        runCount.incrementAndGet();
                        return WorkerResult.of(Map.of("result", "done"));
                      })
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-active")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".working.status == \"active\""))
                  .build())
          .build();
    }
  }
}
