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

import io.casehub.api.model.CaseStatus;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.engine.internal.scheduler.SchedulerService;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Administrative recovery operations for cases in terminal states. Provides the {@link
 * #unfault(UUID)} operation that transitions a FAULTED case back to RUNNING, enabling DLQ replay
 * and resumed execution.
 *
 * <p>Only FAULTED cases are recoverable. CANCELLED cases are intentionally excluded — cancellation
 * is an administrative decision and recovery would undermine its semantics. To retry a cancelled
 * case, start a new instance with the same context.
 */
public class CaseRecoveryService {

  private static final Logger LOG = Logger.getLogger(CaseRecoveryService.class);

  private final CaseInstanceCache caseInstanceCache;
  private final CrossTenantCaseInstanceRepository crossTenantRepository;
  private final CaseCompletionTracker caseCompletionTracker;
  private final EventDispatcher eventDispatcher;
  private final CaseInstanceRepository caseInstanceRepository;
  private final CaseChannelProvider caseChannelProvider;
  private final SchedulerService schedulerService;

  public CaseRecoveryService(
      CaseInstanceCache caseInstanceCache,
      CrossTenantCaseInstanceRepository crossTenantRepository,
      CaseCompletionTracker caseCompletionTracker,
      EventDispatcher eventDispatcher,
      CaseInstanceRepository caseInstanceRepository,
      CaseChannelProvider caseChannelProvider,
      SchedulerService schedulerService) {
    this.caseInstanceCache = caseInstanceCache;
    this.crossTenantRepository = crossTenantRepository;
    this.caseCompletionTracker = caseCompletionTracker;
    this.eventDispatcher = eventDispatcher;
    this.caseInstanceRepository = caseInstanceRepository;
    this.caseChannelProvider = caseChannelProvider;
    this.schedulerService = schedulerService;
  }

  /**
   * Transitions a FAULTED case back to RUNNING. Returns the case instance on success, empty if the
   * case is not found or not FAULTED.
   *
   * <p>CANCELLED cases are intentionally non-recoverable — cancellation is an administrative
   * decision. Start a new instance with the same context to retry.
   *
   * <p>The state is persisted synchronously to the database before returning, eliminating the race
   * between this method and DLQ replay that queries the database directly. The coordination channel
   * is re-opened and scheduled triggers are re-registered. An async {@link CaseStatusChanged} event
   * is dispatched to write the EventLog entry and trigger binding re-evaluation.
   */
  public Optional<CaseInstance> unfault(UUID caseId) {
    CaseInstance instance = caseInstanceCache.get(caseId);
    if (instance == null) {
      instance = crossTenantRepository.findByUuid(caseId).orElse(null);
    }
    if (instance == null) {
      LOG.warnf("Unfault: case not found: %s", caseId);
      return Optional.empty();
    }
    if (instance.getState() == CaseStatus.CANCELLED) {
      LOG.warnf(
          "Cannot recover CANCELLED case %s — cancellation is an administrative "
              + "decision. Start a new instance with the same context to retry.",
          caseId);
      return Optional.empty();
    }
    if (instance.getState() != CaseStatus.FAULTED) {
      LOG.warnf("Unfault: case %s is %s, not FAULTED", caseId, instance.getState());
      return Optional.empty();
    }

    String oldStatus = instance.getState().name();
    instance.setState(CaseStatus.RUNNING);

    caseInstanceRepository.update(instance, instance.tenancyId);
    caseInstanceCache.put(instance);

    caseCompletionTracker.remove(caseId);
    caseCompletionTracker.register(caseId);

    caseChannelProvider.openChannel(caseId, "coordination");
    schedulerService.registerScheduledTriggers(instance);

    eventDispatcher.dispatch(new CaseStatusChanged(instance, oldStatus, CaseStatus.RUNNING.name()));

    LOG.infof("Case unfaulted: caseId=%s (%s → RUNNING)", caseId, oldStatus);
    return Optional.of(instance);
  }
}
