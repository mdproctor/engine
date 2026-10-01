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
package io.casehub.engine.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.engine.internal.scheduler.SchedulerService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseRecoveryServiceTest {

  private CaseInstanceCache cache;
  private CrossTenantCaseInstanceRepository crossTenantRepo;
  private CaseCompletionTracker completionTracker;
  private EventDispatcher eventDispatcher;
  private CaseInstanceRepository caseInstanceRepo;
  private CaseChannelProvider channelProvider;
  private SchedulerService schedulerService;
  private CaseRecoveryService service;

  @BeforeEach
  void setUp() {
    cache = mock(CaseInstanceCache.class);
    crossTenantRepo = mock(CrossTenantCaseInstanceRepository.class);
    completionTracker = mock(CaseCompletionTracker.class);
    eventDispatcher = mock(EventDispatcher.class);
    caseInstanceRepo = mock(CaseInstanceRepository.class);
    channelProvider = mock(CaseChannelProvider.class);
    schedulerService = mock(SchedulerService.class);
    service =
        new CaseRecoveryService(
            cache,
            crossTenantRepo,
            completionTracker,
            eventDispatcher,
            caseInstanceRepo,
            channelProvider,
            schedulerService);
  }

  @Test
  void unfault_persistsStateSynchronously_beforeEventDispatch() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createFaultedInstance(caseId);
    when(cache.get(caseId)).thenReturn(instance);

    service.unfault(caseId);

    var inOrder = inOrder(caseInstanceRepo, eventDispatcher);
    inOrder.verify(caseInstanceRepo).update(instance, instance.tenancyId);
    inOrder.verify(eventDispatcher).dispatch(any());
  }

  @Test
  void unfault_putsInstanceInCache() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createFaultedInstance(caseId);
    when(crossTenantRepo.findByUuid(caseId)).thenReturn(Optional.of(instance));

    service.unfault(caseId);

    verify(cache).put(instance);
  }

  @Test
  void unfault_cancelledCase_returnsEmpty() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.CANCELLED);
    instance.tenancyId = "test-tenant";
    when(cache.get(caseId)).thenReturn(instance);

    Optional<CaseInstance> result = service.unfault(caseId);

    assertThat(result).isEmpty();
    verify(caseInstanceRepo, never()).update(any(), any());
    verify(eventDispatcher, never()).dispatch(any());
  }

  @Test
  void unfault_completedCase_returnsEmpty() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.COMPLETED);
    instance.tenancyId = "test-tenant";
    when(cache.get(caseId)).thenReturn(instance);

    Optional<CaseInstance> result = service.unfault(caseId);

    assertThat(result).isEmpty();
    verify(caseInstanceRepo, never()).update(any(), any());
  }

  @Test
  void unfault_reopensChannelAndReregistersScheduledTriggers() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createFaultedInstance(caseId);
    when(cache.get(caseId)).thenReturn(instance);

    service.unfault(caseId);

    verify(channelProvider).openChannel(caseId, "coordination");
    verify(schedulerService).registerScheduledTriggers(instance);
  }

  @Test
  void unfault_cacheMiss_loadsFromCrossTenantRepo() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createFaultedInstance(caseId);
    when(cache.get(caseId)).thenReturn(null);
    when(crossTenantRepo.findByUuid(caseId)).thenReturn(Optional.of(instance));

    Optional<CaseInstance> result = service.unfault(caseId);

    assertThat(result).isPresent();
    assertThat(result.get().getState()).isEqualTo(CaseStatus.RUNNING);
    verify(crossTenantRepo).findByUuid(caseId);
    verify(caseInstanceRepo).update(instance, "test-tenant");
    verify(cache).put(instance);
  }

  @Test
  void unfault_fullOrdering_persistBeforeChannelBeforeTriggerBeforeDispatch() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = createFaultedInstance(caseId);
    when(cache.get(caseId)).thenReturn(instance);

    service.unfault(caseId);

    var inOrder =
        inOrder(caseInstanceRepo, cache, channelProvider, schedulerService, eventDispatcher);
    inOrder.verify(caseInstanceRepo).update(instance, "test-tenant");
    inOrder.verify(cache).put(instance);
    inOrder.verify(channelProvider).openChannel(caseId, "coordination");
    inOrder.verify(schedulerService).registerScheduledTriggers(instance);
    inOrder.verify(eventDispatcher).dispatch(any());
  }

  @Test
  void unfault_unknownCase_returnsEmpty() {
    UUID caseId = UUID.randomUUID();
    when(cache.get(caseId)).thenReturn(null);
    when(crossTenantRepo.findByUuid(caseId)).thenReturn(Optional.empty());

    Optional<CaseInstance> result = service.unfault(caseId);

    assertThat(result).isEmpty();
    verify(caseInstanceRepo, never()).update(any(), any());
  }

  private CaseInstance createFaultedInstance(UUID caseId) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.FAULTED);
    instance.tenancyId = "test-tenant";
    return instance;
  }
}
