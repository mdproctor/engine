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
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.model.RecoveryPolicy;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.PlanVersionStore;
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
import org.junit.jupiter.api.Timeout;

@QuarkusTest
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RecoveryPipelineIntegrationTest {

  @Inject RecoveryEnabledCaseHub recoveryEnabledBean;
  @Inject NoRecoveryCaseHub noRecoveryBean;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventLogRepository eventLogRepository;
  @Inject PlanVersionStore planVersionStore;

  @Test
  void recoveryInterceptsExhaustionAndWritesReplanEvent() {
    UUID caseId = recoveryEnabledBean.startCase(Map.of("task", "pending"));

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<EventLog> replanEvents =
                  eventLogRepository.findByCaseAndTypes(
                      caseId,
                      List.of(CaseHubEventType.RECOVERY_REPLAN),
                      TenancyConstants.DEFAULT_TENANT_ID);
              assertThat(replanEvents)
                  .as("Recovery Level 3 should produce a RECOVERY_REPLAN event")
                  .isNotEmpty();
              assertThat(replanEvents.get(0).getMetadata().get("classifiedLevel").asText())
                  .isEqualTo("FUNDAMENTAL");
            });
  }

  @Test
  void recoveryStoresPlanVersion() {
    UUID caseId = recoveryEnabledBean.startCase(Map.of("task", "pending"));

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var history = planVersionStore.getHistory(caseId, TenancyConstants.DEFAULT_TENANT_ID);
              assertThat(history)
                  .as("Recovery should store at least one plan version")
                  .isNotEmpty();
              assertThat(history.get(0).version()).isEqualTo(1);
              assertThat(history.get(0).trigger())
                  .isInstanceOf(
                      io.casehub.engine.plan.snapshot.PlanVersionTrigger.CaseReplan.class);
            });
  }

  @Test
  void withoutRecoveryPolicyCaseFaultsNormally() {
    UUID caseId = noRecoveryBean.startCase(Map.of("task", "pending"));

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .as("Case should FAULT when no recovery policy is configured")
                    .isEqualTo(CaseStatus.FAULTED));

    List<EventLog> replanEvents =
        eventLogRepository.findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.RECOVERY_REPLAN), TenancyConstants.DEFAULT_TENANT_ID);
    assertThat(replanEvents)
        .as("No recovery events should exist without recovery policy")
        .isEmpty();
  }

  @ApplicationScoped
  public static class RecoveryEnabledCaseHub extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("recovery-cap")
            .inputSchema("{ task: .task }")
            .outputSchema(".")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-recovery-pipeline")
          .name("Recovery Pipeline Test")
          .version("1.0.0")
          .recoveryPolicy(RecoveryPolicy.DEFAULT)
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("always-declining-recovery")
                  .capabilityName("recovery-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.declined("cannot handle this")))
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.REROUTE, 1))
                  .on(new ContextChangeTrigger(".task == \"pending\""))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  public static class NoRecoveryCaseHub extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("no-recovery-cap")
            .inputSchema("{ task: .task }")
            .outputSchema(".")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-no-recovery")
          .name("No Recovery Test")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("always-declining-no-recovery")
                  .capabilityName("no-recovery-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.declined("cannot handle this")))
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.FAULT, OutcomeAction.FAULT, OutcomeAction.FAULT, 1))
                  .on(new ContextChangeTrigger(".task == \"pending\""))
                  .build())
          .build();
    }
  }
}
