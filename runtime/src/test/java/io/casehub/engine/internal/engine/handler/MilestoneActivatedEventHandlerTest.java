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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.engine.common.internal.event.MilestoneActivatedEvent;
import io.casehub.engine.common.internal.event.MilestoneSLAViolatedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MilestoneActivatedEventHandlerTest {

  private MilestoneActivatedEventHandler handler;
  private final List<Object> dispatched = new ArrayList<>();
  private EventLogRepository eventLogRepo;
  private JobScheduler scheduler;
  private LedgerTraceIdProvider traceIdProvider;

  @BeforeEach
  void setUp() {
    dispatched.clear();
    eventLogRepo = mock(EventLogRepository.class);
    scheduler = mock(JobScheduler.class);
    traceIdProvider = mock(LedgerTraceIdProvider.class);

    when(traceIdProvider.currentTraceId()).thenReturn(Optional.empty());

    handler =
        new MilestoneActivatedEventHandler(
            eventLogRepo, dispatched::add, scheduler, e -> {}, traceIdProvider);
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

    handler.handle(event);

    org.assertj.core.api.Assertions.assertThat(dispatched)
        .anySatisfy(
            e ->
                org.assertj.core.api.Assertions.assertThat(e)
                    .isInstanceOf(MilestoneSLAViolatedEvent.class));
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

    handler.handle(event);

    verify(scheduler).schedule(any(ScheduledJobRequest.Builder.class));
    org.assertj.core.api.Assertions.assertThat(
            dispatched.stream().filter(e -> e instanceof MilestoneSLAViolatedEvent).toList())
        .isEmpty();
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

    handler.handle(event);

    verify(scheduler, never()).schedule(any(ScheduledJobRequest.Builder.class));
    org.assertj.core.api.Assertions.assertThat(
            dispatched.stream().filter(e -> e instanceof MilestoneSLAViolatedEvent).toList())
        .isEmpty();
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
}
