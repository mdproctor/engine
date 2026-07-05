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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: verifies that WORKER_EXECUTION_COMPLETED EventLog entries contain a {@code
 * contextChanges} field reflecting the before/after state of CaseContext keys modified by the
 * worker.
 */
@QuarkusTest
class ContextDiffEndToEndTest {

  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject DiffCaseHub diffCase;
  @Inject UpdateCaseHub updateCase;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  /**
   * Happy path: initial context has "status"="start". Worker writes "status"="done" and adds
   * "result"="ok". EventLog must record status as updated, result as added.
   */
  @Test
  void workerOutput_isReflectedInContextChanges() {
    AtomicReference<UUID> caseIdRef = new AtomicReference<>();

    diffCase
        .startCase(Map.of("status", "start"))
        .thenAccept(caseIdRef::set)
        .toCompletableFuture()
        .join();

    UUID caseId = caseIdRef.get();

    // Wait for COMPLETED
    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });

    // Query the WORKER_EXECUTION_COMPLETED EventLog entry
    EventLog completedEvent = fetchCompletedEvent(caseId);
    assertThat(completedEvent).isNotNull();

    var metadata = completedEvent.getMetadata();
    assertThat(metadata.has("contextChanges")).isTrue();

    var changes = metadata.get("contextChanges");

    // After panels migration, top-level diff key is "working" (the panel name).
    // Before = working panel before worker ran; after = working panel after worker ran.
    assertThat(changes.has("working")).isTrue();
    var workingDiff = changes.get("working");

    // "status" was updated: before="start", after="done"
    assertThat(workingDiff.get("before").get("status").asText()).isEqualTo("start");
    assertThat(workingDiff.get("after").get("status").asText()).isEqualTo("done");

    // "result" was added: not present before, present after
    assertThat(workingDiff.get("before").has("result")).isFalse();
    assertThat(workingDiff.get("after").get("result").asText()).isEqualTo("ok");
  }

  /**
   * Keys present in initial context that the worker does NOT touch must be absent from
   * contextChanges.
   */
  @Test
  void unchangedKeys_areAbsentFromContextChanges() {
    AtomicReference<UUID> caseIdRef = new AtomicReference<>();

    // Start with two keys; worker only touches "status"
    updateCase
        .startCase(Map.of("status", "start", "unchanged", "keep-me"))
        .thenAccept(caseIdRef::set)
        .toCompletableFuture()
        .join();

    UUID caseId = caseIdRef.get();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });

    EventLog completedEvent = fetchCompletedEvent(caseId);
    var changes = completedEvent.getMetadata().get("contextChanges");

    // After panels, "working" panel changed (status updated); no top-level "unchanged" key.
    assertThat(changes.has("working")).isTrue();
    assertThat(changes.has("unchanged")).isFalse();
  }

  /** The inputDataHash must still be present alongside contextChanges. */
  @Test
  void inputDataHash_remainsPresentAlongsideContextChanges() {
    AtomicReference<UUID> caseIdRef = new AtomicReference<>();
    diffCase
        .startCase(Map.of("status", "start"))
        .thenAccept(caseIdRef::set)
        .toCompletableFuture()
        .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseIdRef.get());
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });

    EventLog completedEvent = fetchCompletedEvent(caseIdRef.get());
    var metadata = completedEvent.getMetadata();

    assertThat(metadata.has("inputDataHash")).isTrue();
    assertThat(metadata.get("inputDataHash").asText()).isNotBlank();
    assertThat(metadata.has("contextChanges")).isTrue();
  }

  // ---- Helper ---------------------------------------------------------------

  private EventLog fetchCompletedEvent(UUID caseId) {
    List<EventLog> events =
        reactiveEventLogRepository
            .findByCaseAndTypes(
                caseId,
                List.of(CaseHubEventType.WORKER_EXECUTION_COMPLETED),
                TenancyConstants.DEFAULT_TENANT_ID)
            .await()
            .atMost(SPI_TIMEOUT);
    return events.isEmpty() ? null : events.get(0);
  }

  // ---- CaseHub beans --------------------------------------------------------

  /** Worker writes status="done" and adds result="ok". */
  @ApplicationScoped
  public static class DiffCaseHub extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("doWork")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status, result: .result }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".status == \"done\"").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-context-diff")
          .name("Context Diff Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("diff-worker")
                  .capabilityName("doWork")
                  .function(
                      new WorkerFunction.Sync(
                          input -> WorkerResult.of(Map.of("status", "done", "result", "ok"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"start\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }

  /** Worker writes only status="done"; leaves "unchanged" key untouched. */
  @ApplicationScoped
  public static class UpdateCaseHub extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("partialUpdate")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".status == \"done\"").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-context-diff-partial")
          .name("Partial Update Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("partial-worker")
                  .capabilityName("partialUpdate")
                  .function(
                      new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("status", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"start\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
