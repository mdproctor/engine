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
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
 * Verifies that {@code casehub.engine.diff-strategy=none} (the default) causes {@code
 * WORKER_EXECUTION_COMPLETED} EventLog entries to omit the {@code contextChanges} field entirely,
 * even when the worker modifies context.
 */
@QuarkusTest
@TestProfile(ContextDiffNoneStrategyTest.NoneStrategyProfile.class)
class ContextDiffNoneStrategyTest {

  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject NoneStrategyCaseHub noneCase;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  @Test
  void noneStrategy_workerModifiesContext_contextChangesAbsent() {
    final AtomicReference<UUID> caseIdRef = new AtomicReference<>();

    noneCase
        .startCase(Map.of("status", "start"))
        .thenAccept(caseIdRef::set)
        .toCompletableFuture()
        .join();

    final UUID caseId = caseIdRef.get();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });

    final List<EventLog> events =
        reactiveEventLogRepository
            .findByCaseAndTypes(
                caseId,
                List.of(CaseHubEventType.WORKER_EXECUTION_COMPLETED),
                TenancyConstants.DEFAULT_TENANT_ID)
            .await()
            .atMost(SPI_TIMEOUT);

    assertThat(events).isNotEmpty();
    assertThat(events.get(0).getMetadata().has("contextChanges")).isFalse();
  }

  @ApplicationScoped
  public static class NoneStrategyCaseHub extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("doWork")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".status == \"done\"").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-context-diff-none")
          .name("Context Diff None Strategy Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("none-worker")
                  .capabilityName("doWork")
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

  public static class NoneStrategyProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("casehub.engine.diff-strategy", "none");
    }
  }
}
