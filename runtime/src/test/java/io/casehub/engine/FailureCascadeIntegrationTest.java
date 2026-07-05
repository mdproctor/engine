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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the failure cascade FAULT policy path (engine#504).
 *
 * <p>Reroute tests require the blackboard module (WorkerOutcomeResolvedHandler) and live in
 * casehub-blackboard's test suite. This test verifies the FAULT policy path which works without the
 * blackboard — handleSemanticFailure publishes CASE_STATUS_CHANGED(FAULTED) directly.
 */
@QuarkusTest
class FailureCascadeIntegrationTest {

  @Inject FaultPolicyBean faultPolicyBean;
  @Inject ExpiredFaultPolicyBean expiredFaultPolicyBean;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  @Test
  void fault_policy_faults_case_immediately_on_decline() {
    UUID caseId = faultPolicyBean.startCase(Map.of("task", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    CaseStatus.FAULTED,
                    caseInstanceCache.get(caseId).getState(),
                    "Case must be FAULTED when OutcomePolicy is FAULT"));
  }

  @Test
  void fault_policy_produces_worker_outcome_declined_event_log() {
    UUID caseId = faultPolicyBean.startCase(Map.of("task", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertEquals(CaseStatus.FAULTED, caseInstanceCache.get(caseId).getState()));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<EventLog> declined =
                  findEvents(caseId, CaseHubEventType.WORKER_OUTCOME_DECLINED);
              assertEquals(
                  1, declined.size(), "Exactly one WORKER_OUTCOME_DECLINED event log entry");
              assertEquals("FAULT", declined.get(0).getMetadata().get("disposition").asText());
            });
  }

  @Test
  void expired_with_fault_policy_faults_case() {
    UUID caseId =
        expiredFaultPolicyBean.startCase(Map.of("task", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    CaseStatus.FAULTED,
                    caseInstanceCache.get(caseId).getState(),
                    "Case must be FAULTED when worker times out and onExpired is FAULT"));
  }

  @Test
  void expired_produces_worker_outcome_expired_event_log() {
    UUID caseId =
        expiredFaultPolicyBean.startCase(Map.of("task", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertEquals(CaseStatus.FAULTED, caseInstanceCache.get(caseId).getState()));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<EventLog> expired = findEvents(caseId, CaseHubEventType.WORKER_OUTCOME_EXPIRED);
              assertEquals(1, expired.size(), "Exactly one WORKER_OUTCOME_EXPIRED event log entry");
              assertEquals("FAULT", expired.get(0).getMetadata().get("disposition").asText());
            });
  }

  @Inject SuccessAfterRerouteBean successAfterRerouteBean;

  @Test
  @SuppressWarnings("unchecked")
  void success_after_reroute_records_completed_in_outcomes() {
    Map<String, Object> priorOutcomes =
        Map.of(
            "on-task",
            Map.of(
                "status",
                "DECLINED",
                "attempts",
                1,
                "history",
                List.of(Map.of("agent", "first-worker", "status", "DECLINED", "reason", "nope")),
                "excludedAgents",
                List.of("first-worker")));

    UUID caseId =
        successAfterRerouteBean
            .startCase(Map.of("task", "go", "_outcomes", priorOutcomes))
            .toCompletableFuture()
            .join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              Map<String, Object> outcomes =
                  (Map<String, Object>) instance.getCaseContext().get("_outcomes");
              assertNotNull(outcomes, "_outcomes must exist");
              Map<String, Object> binding = (Map<String, Object>) outcomes.get("on-task");
              assertNotNull(binding, "_outcomes.on-task must exist");
              assertEquals("COMPLETED", binding.get("status"));
              List<Map<String, Object>> history =
                  (List<Map<String, Object>>) binding.get("history");
              assertTrue(history.size() >= 2, "History must have at least 2 entries");
              Map<String, Object> lastEntry = history.get(history.size() - 1);
              assertEquals("COMPLETED", lastEntry.get("status"));
              assertEquals("succeeding-worker", lastEntry.get("agent"));
            });
  }

  private List<EventLog> findEvents(UUID caseId, CaseHubEventType eventType) {
    return reactiveEventLogRepository
        .findByCaseAndTypes(caseId, List.of(eventType), TenancyConstants.DEFAULT_TENANT_ID)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();
  }

  @ApplicationScoped
  public static class SuccessAfterRerouteBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("success-cap")
            .inputSchema("{ task: .task }")
            .outputSchema(".")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-success-after-reroute")
          .name("Success After Reroute Test")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("succeeding-worker")
                  .capabilityName("success-cap")
                  .function(
                      new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("result", "done"))))
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.REROUTE, 3))
                  .on(new ContextChangeTrigger(".task == \"go\""))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  public static class FaultPolicyBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("fault-cap")
            .inputSchema("{ task: .task }")
            .outputSchema(".")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-failure-cascade-fault")
          .name("Fault Policy Test")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("always-declining")
                  .capabilityName("fault-cap")
                  .function(
                      new WorkerFunction.Sync(input -> WorkerResult.declined("cannot handle")))
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.FAULT, OutcomeAction.FAULT, OutcomeAction.REROUTE, 1))
                  .on(new ContextChangeTrigger(".task == \"pending\""))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  public static class ExpiredFaultPolicyBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("expired-cap")
            .inputSchema("{ task: .task }")
            .outputSchema(".")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-failure-cascade-expired")
          .name("Expired Fault Policy Test")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("slow-worker")
                  .capabilityName("expired-cap")
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            try {
                              Thread.sleep(5000);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                            return WorkerResult.of(Map.of("result", "late"));
                          }))
                  .executionPolicy(new ExecutionPolicy(200, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.FAULT, 1))
                  .on(new ContextChangeTrigger(".task == \"pending\""))
                  .build())
          .build();
    }
  }
}
