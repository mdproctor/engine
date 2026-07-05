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

import io.casehub.api.context.ContextLayer;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.SlaStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MilestoneLifecycleTest {

  @Inject BasicLifecycleTestBean basicLifecycleBean;
  @Inject SlaHappyPathTestBean slaHappyPathBean;
  @Inject LateCompletionTestBean lateCompletionBean;
  @Inject ReplayTestBean replayBean;
  @Inject ConditionalActivationTestBean conditionalActivationBean;
  @Inject SlaIdempotencyTestBean slaIdempotencyBean;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject WorkerExecutionRecoveryService recoveryService;
  @Inject ReactiveEventLogRepository reactiveEventLogRepository;
  @Inject io.vertx.mutiny.core.eventbus.EventBus eventBus;

  @Test
  void milestoneLifecycle_pendingToActiveToCompleted() {
    UUID caseId =
        basicLifecycleBean
            .startCase(Map.of("requestSubmitted", false))
            .toCompletableFuture()
            .join();

    // Milestone starts PENDING (entryCriteria not met)
    assertMilestoneStatus(
        caseId, "approval", MilestoneLifecycleStatus.PENDING, SlaStatus.NOT_STARTED);

    // Trigger entry
    updateContext(caseId, "requestSubmitted", true);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.ACTIVE, SlaStatus.ON_TRACK));

    // Trigger completion
    updateContext(caseId, "approved", true);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.COMPLETED, SlaStatus.ON_TRACK));
  }

  @Test
  void milestoneSLA_firesWhenDeadlinePassed() {
    UUID caseId = slaHappyPathBean.startCase(Map.of()).toCompletableFuture().join();

    // Milestone activates immediately (entryCriteria defaults to true)
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.ACTIVE, SlaStatus.ON_TRACK));

    // Wait for SLA violation (2 seconds + buffer)
    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.ACTIVE, SlaStatus.BREACHED));

    // Verify event was published to EventLog
    // TODO: add EventLog assertion helper
  }

  @Test
  void milestoneSLA_completedAfterBreach() {
    UUID caseId = lateCompletionBean.startCase(Map.of()).toCompletableFuture().join();

    // Wait for SLA violation
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.ACTIVE, SlaStatus.BREACHED));

    // Complete milestone after deadline
    updateContext(caseId, "approved", true);

    // Verify: COMPLETED + BREACHED (both true)
    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.COMPLETED, SlaStatus.BREACHED));
  }

  @Test
  void milestoneState_reconstructedFromEventLog() {
    UUID caseId = replayBean.startCase(Map.of()).toCompletableFuture().join();

    // Activate and complete milestone
    updateContext(caseId, "approved", true);

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.COMPLETED, SlaStatus.ON_TRACK));

    // Simulate restart: clear cache
    caseInstanceCache.clear();

    // Reload from repository (triggers rebuildStateContext)
    CaseInstance reloaded =
        recoveryService.loadOrRestoreCaseInstance(caseId).await().indefinitely();

    // Verify milestone state reconstructed in CaseContext
    @SuppressWarnings("unchecked")
    Map<String, Object> milestones = (Map) reloaded.getCaseContext().get("milestones");
    assertThat(milestones).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> approval = (Map) milestones.get("approval");
    assertThat(approval).isNotNull();
    assertThat(approval.get("lifecycleStatus")).isEqualTo("COMPLETED");
    assertThat(approval.get("slaStatus")).isEqualTo("ON_TRACK");
    assertThat(approval.get("completedAt")).isNotNull();
  }

  @Test
  void milestoneEntryCriteria_blocksActivation() {
    UUID caseId =
        conditionalActivationBean.startCase(Map.of("stage", "draft")).toCompletableFuture().join();

    // Milestone remains PENDING (entryCriteria not met)
    assertMilestoneStatus(
        caseId, "review", MilestoneLifecycleStatus.PENDING, SlaStatus.NOT_STARTED);

    // Trigger activation
    updateContext(caseId, "stage", "review");

    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "review", MilestoneLifecycleStatus.ACTIVE, SlaStatus.ON_TRACK));
  }

  @Test
  void slaJob_doesNotFireAfterCompletion() throws Exception {
    UUID caseId = slaIdempotencyBean.startCase(Map.of()).toCompletableFuture().join();

    // Milestone activates immediately
    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.ACTIVE, SlaStatus.ON_TRACK));

    // Complete milestone quickly (before 5s SLA)
    Thread.sleep(1000);
    updateContext(caseId, "approved", true);

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertMilestoneStatus(
                    caseId, "approval", MilestoneLifecycleStatus.COMPLETED, SlaStatus.ON_TRACK));

    // Wait past SLA deadline (6s total)
    Thread.sleep(5000);

    // Verify no SLA_VIOLATED event
    java.util.List<EventLog> slaEvents =
        reactiveEventLogRepository
            .findByCaseAndTypes(
                caseId,
                java.util.EnumSet.of(CaseHubEventType.MILESTONE_SLA_VIOLATED),
                TenancyConstants.DEFAULT_TENANT_ID)
            .await()
            .indefinitely();

    assertThat(slaEvents).isEmpty();
  }

  private void updateContext(UUID caseId, String key, Object value) {
    CaseInstance instance = caseInstanceCache.get(caseId);
    instance.getCaseContext().set(key, value);

    // Publish context changed event to trigger milestone evaluation
    CaseContextChangedEvent event =
        new CaseContextChangedEvent(
            instance, instance.getCaseContext().snapshot(), ContextLayer.WORKING);
    eventBus.publish(EventBusAddresses.CONTEXT_CHANGED, event);
  }

  @SuppressWarnings("unchecked")
  private void assertMilestoneStatus(
      UUID caseId, String milestoneName, MilestoneLifecycleStatus lifecycle, SlaStatus sla) {

    CaseInstance instance = caseInstanceCache.get(caseId);
    Map<String, Object> milestones = (Map) instance.getCaseContext().get("milestones");

    if (lifecycle == MilestoneLifecycleStatus.PENDING) {
      // PENDING milestones may not have entry in context yet
      if (milestones == null || !milestones.containsKey(milestoneName)) {
        return;
      }
    }

    assertThat(milestones).isNotNull();
    Map<String, Object> milestone = (Map) milestones.get(milestoneName);
    assertThat(milestone).isNotNull();
    assertThat(milestone.get("lifecycleStatus")).isEqualTo(lifecycle.name());
    assertThat(milestone.get("slaStatus")).isEqualTo(sla.name());
  }

  @ApplicationScoped
  static class BasicLifecycleTestBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .name("basic-lifecycle-test")
          .namespace("test")
          .version("1.0")
          .milestones(
              Milestone.builder()
                  .name("approval")
                  .entryCriteria(".requestSubmitted == true")
                  .completionCriteria(".approved == true")
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class SlaHappyPathTestBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .name("sla-happy-path-test")
          .namespace("test")
          .version("1.0")
          .milestones(
              Milestone.builder()
                  .name("approval")
                  .completionCriteria(".approved == true")
                  .slaDuration(java.time.Duration.ofSeconds(2))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class LateCompletionTestBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .name("late-completion-test")
          .namespace("test")
          .version("1.0")
          .milestones(
              Milestone.builder()
                  .name("approval")
                  .completionCriteria(".approved == true")
                  .slaDuration(java.time.Duration.ofSeconds(2))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class ReplayTestBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .name("replay-test")
          .namespace("test")
          .version("1.0")
          .milestones(
              Milestone.builder()
                  .name("approval")
                  .completionCriteria(".approved == true")
                  .slaDuration(java.time.Duration.ofHours(24))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class ConditionalActivationTestBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .name("conditional-activation-test")
          .namespace("test")
          .version("1.0")
          .milestones(
              Milestone.builder()
                  .name("review")
                  .entryCriteria(".stage == \"review\"")
                  .completionCriteria(".reviewComplete == true")
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class SlaIdempotencyTestBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .name("sla-idempotency-test")
          .namespace("test")
          .version("1.0")
          .milestones(
              Milestone.builder()
                  .name("approval")
                  .completionCriteria(".approved == true")
                  .slaDuration(java.time.Duration.ofSeconds(5))
                  .build())
          .build();
    }
  }
}
