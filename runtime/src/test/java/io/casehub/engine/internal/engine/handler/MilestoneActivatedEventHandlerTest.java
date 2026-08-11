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
package io.casehub.engine.internal.engine.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneActivatedEvent;
import io.casehub.engine.common.internal.event.MilestoneSLAViolatedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MilestoneActivatedEventHandlerTest {

  private MilestoneActivatedEventHandler handler;
  private EventBus eventBus;
  private EventLogRepository eventLogRepo;
  private JobScheduler scheduler;
  private Event<CaseLifecycleEvent> lifecycleEvents;
  private LedgerTraceIdProvider traceIdProvider;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    handler = new MilestoneActivatedEventHandler();
    eventBus = mock(EventBus.class);
    eventLogRepo = mock(EventLogRepository.class);
    scheduler = mock(JobScheduler.class);
    lifecycleEvents = mock(Event.class);
    traceIdProvider = mock(LedgerTraceIdProvider.class);

    inject(handler, "eventBus", eventBus);
    inject(handler, "eventLogRepository", eventLogRepo);
    inject(handler, "scheduler", scheduler);
    inject(handler, "lifecycleEvents", lifecycleEvents);
    inject(handler, "traceIdProvider", traceIdProvider);

    // eventLogRepo.append is void — no stub needed
    when(traceIdProvider.currentTraceId()).thenReturn(Optional.empty());
    when(lifecycleEvents.fireAsync(any()))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
  }

  @Test
  void deadlineAlreadyPassed_firesImmediateViolation() {
    CaseInstance caseInstance = createCaseInstance();
    Milestone milestone =
        Milestone.builder()
            .name("review-complete")
            .completionCriteria(".reviewed == true")
            .slaDuration(Duration.ofHours(1))
            .build();
    Instant activatedAt = Instant.now();
    Instant pastDeadline = Instant.now().minus(Duration.ofMinutes(30));

    setMilestoneActive(caseInstance, "review-complete");

    MilestoneActivatedEvent event =
        new MilestoneActivatedEvent(caseInstance, milestone, activatedAt, pastDeadline);

    handler.onMilestoneActivated(event);

    verify(eventBus)
        .publish(
            eq(EventBusAddresses.MILESTONE_SLA_VIOLATED),
            argThat(
                arg -> {
                  MilestoneSLAViolatedEvent e = (MilestoneSLAViolatedEvent) arg;
                  return e.caseInstance() == caseInstance
                      && "review-complete".equals(e.milestoneName());
                }));
    verify(scheduler, never()).schedule(any(ScheduledJobRequest.Builder.class));
  }

  @Test
  void futureDeadline_schedulesJob() {
    CaseInstance caseInstance = createCaseInstance();
    Milestone milestone =
        Milestone.builder()
            .name("review-complete")
            .completionCriteria(".reviewed == true")
            .slaDuration(Duration.ofHours(1))
            .build();
    Instant activatedAt = Instant.now();
    Instant futureDeadline = Instant.now().plus(Duration.ofHours(2));

    setMilestoneActive(caseInstance, "review-complete");
    doNothing().when(scheduler).schedule(any(ScheduledJobRequest.Builder.class));

    MilestoneActivatedEvent event =
        new MilestoneActivatedEvent(caseInstance, milestone, activatedAt, futureDeadline);

    handler.onMilestoneActivated(event);

    verify(scheduler).schedule(any(ScheduledJobRequest.Builder.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.MILESTONE_SLA_VIOLATED), any());
  }

  @Test
  void noSlaDeadline_skipsScheduling() {
    CaseInstance caseInstance = createCaseInstance();
    Milestone milestone =
        Milestone.builder().name("review-complete").completionCriteria(".reviewed == true").build();
    Instant activatedAt = Instant.now();

    setMilestoneActive(caseInstance, "review-complete");

    MilestoneActivatedEvent event =
        new MilestoneActivatedEvent(caseInstance, milestone, activatedAt, null);

    handler.onMilestoneActivated(event);

    verify(scheduler, never()).schedule(any(ScheduledJobRequest.Builder.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.MILESTONE_SLA_VIOLATED), any());
  }

  private CaseInstance createCaseInstance() {
    CaseInstance ci = new CaseInstance();
    ci.setUuid(UUID.randomUUID());
    ci.tenancyId = "test-tenant";
    ci.setCaseContext(new CaseContextImpl());
    return ci;
  }

  private void setMilestoneActive(CaseInstance ci, String milestoneName) {
    CaseContext ctx = ci.getCaseContext();
    ctx.setPath(
        "milestones." + milestoneName + ".lifecycleStatus", MilestoneLifecycleStatus.ACTIVE.name());
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
