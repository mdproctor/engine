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
package io.casehub.resilience.timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.cache.CaseInstanceCacheImpl;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests — no Quarkus, no CDI. Timeout detection is driven by {@link
 * PropagationContext#isBudgetExhausted()}. Tests set an exhausted or healthy context directly on
 * the instance rather than manipulating wall-clock time.
 */
class CaseTimeoutEnforcerTest {

  private CaseInstanceCache cache;
  private EventBus eventBus;
  private ReactiveCaseInstanceRepository repository;
  private CaseTimeoutEnforcer enforcer;

  @BeforeEach
  void setUp() {
    cache = new CaseInstanceCacheImpl();
    eventBus = mock(EventBus.class);
    repository = mock(ReactiveCaseInstanceRepository.class);
    when(repository.updateStateAndAppendEvent(any(), any(), any()))
        .thenReturn(Uni.createFrom().voidItem());
    enforcer = new CaseTimeoutEnforcer(cache, eventBus, repository);
  }

  // ---- Helpers ---------------------------------------------------------------

  private CaseInstance runningInstance() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    instance.setPropagationContext(healthyContext());
    return instance;
  }

  private PropagationContext healthyContext() {
    return PropagationContext.createRoot(Map.of(), Duration.ofHours(1));
  }

  private PropagationContext exhaustedContext() {
    return PropagationContext.createRoot(Map.of(), Duration.ZERO);
  }

  // ---- No-op cases ----------------------------------------------------------

  @Test
  void emptyCache_scanForTimeouts_doesNothing() {
    enforcer.scanForTimeouts(); // must not throw
  }

  @Test
  void runningCase_withinBudget_remainsRunning() {
    CaseInstance instance = runningInstance(); // healthy context with 1h budget
    cache.put(instance);

    enforcer.scanForTimeouts();

    assertThat(instance.getState()).isEqualTo(CaseStatus.RUNNING);
  }

  @Test
  void runningCase_noPropagationContext_isSkipped() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    // no PropagationContext — simulates a case restored from persistence before this feature
    cache.put(instance);

    enforcer.scanForTimeouts();

    assertThat(instance.getState()).isEqualTo(CaseStatus.RUNNING);
    verify(repository, never()).updateStateAndAppendEvent(any(), any(), any());
  }

  // ---- Timeout cases --------------------------------------------------------

  @Test
  void runningCase_exhaustedBudget_isTransitionedToFaulted() {
    CaseInstance instance = runningInstance();
    instance.setPropagationContext(exhaustedContext());
    cache.put(instance);

    enforcer.scanForTimeouts();

    assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
  }

  @Test
  void timedOutCase_statusChangedEventIsPublished() {
    CaseInstance instance = runningInstance();
    instance.setPropagationContext(exhaustedContext());
    cache.put(instance);

    enforcer.scanForTimeouts();

    verify(eventBus)
        .publish(eq(EventBusAddresses.CASE_STATUS_CHANGED), any(CaseStatusChanged.class));
  }

  @Test
  void timedOutCase_persistenceIsInvoked() {
    CaseInstance instance = runningInstance();
    instance.setPropagationContext(exhaustedContext());
    cache.put(instance);

    enforcer.scanForTimeouts();

    verify(repository).updateStateAndAppendEvent(any(), any(), any());
  }

  // ---- Non-RUNNING cases are ignored ----------------------------------------

  @Test
  void completedCase_isNeverFaulted() {
    CaseInstance instance = runningInstance();
    instance.setPropagationContext(exhaustedContext());
    instance.setState(CaseStatus.COMPLETED);
    cache.put(instance);

    enforcer.scanForTimeouts();

    assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
  }

  @Test
  void waitingCase_isNeverFaulted() {
    CaseInstance instance = runningInstance();
    instance.setPropagationContext(exhaustedContext());
    instance.setState(CaseStatus.WAITING);
    cache.put(instance);

    enforcer.scanForTimeouts();

    assertThat(instance.getState()).isEqualTo(CaseStatus.WAITING);
  }

  // ---- Multiple cases -------------------------------------------------------

  @Test
  void multipleCases_onlyExhaustedOnesAreFaulted() {
    CaseInstance overdue = runningInstance();
    overdue.setPropagationContext(exhaustedContext());

    CaseInstance healthy = runningInstance();
    // healthy context already set by runningInstance()

    cache.put(overdue);
    cache.put(healthy);

    enforcer.scanForTimeouts();

    assertThat(overdue.getState()).isEqualTo(CaseStatus.FAULTED);
    assertThat(healthy.getState()).isEqualTo(CaseStatus.RUNNING);
  }

  @Test
  void secondScan_alreadyFaultedCase_isNotProcessedAgain() {
    CaseInstance instance = runningInstance();
    instance.setPropagationContext(exhaustedContext());
    cache.put(instance);

    enforcer.scanForTimeouts(); // faults the case
    assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);

    enforcer.scanForTimeouts(); // state is FAULTED (not RUNNING) — must be skipped
    assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
  }
}
