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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.scheduler.Scheduled;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Scheduled scanner that faults RUNNING cases whose {@link
 * io.casehub.api.context.PropagationContext} budget is exhausted.
 *
 * <p>The budget deadline is set at case creation time by {@code CaseHubReactor} using the {@code
 * casehub.resilience.timeout.max-duration} config property. Cases without a propagation context
 * (e.g. restored from persistence before this feature was introduced) are skipped — no budget means
 * no timeout.
 *
 * <p>Cases that exceed the budget are transitioned to {@link CaseStatus#FAULTED}. A {@link
 * EventBusAddresses#CASE_STATUS_CHANGED} event is published, which causes {@code
 * CaseStatusChangedHandler} to persist the final state and emit {@link
 * EventBusAddresses#CASE_FAULTED}.
 */
@ApplicationScoped
public class CaseTimeoutEnforcer {

  private static final Logger LOG = Logger.getLogger(CaseTimeoutEnforcer.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final CaseInstanceCache cache;
  private final EventBus eventBus;
  private final CaseInstanceRepository caseInstanceRepository;

  @Inject
  public CaseTimeoutEnforcer(
      CaseInstanceCache cache, EventBus eventBus, CaseInstanceRepository caseInstanceRepository) {
    this.cache = cache;
    this.eventBus = eventBus;
    this.caseInstanceRepository = caseInstanceRepository;
  }

  /**
   * Scheduled scan. Iterates all cached RUNNING cases and faults any whose {@code
   * PropagationContext} budget is exhausted.
   *
   * <p>Called every second by default; override with {@code
   * casehub.resilience.timeout.check-interval}.
   */
  @Scheduled(every = "${casehub.resilience.timeout.check-interval:1s}")
  public void scanForTimeouts() {
    Instant now = Instant.now();
    for (CaseInstance instance : cache.getAll()) {
      if (instance.getState() != CaseStatus.RUNNING) {
        continue;
      }
      if (instance.getPropagationContext() == null
          || !instance.getPropagationContext().isBudgetExhausted()) {
        continue;
      }

      UUID caseId = instance.getUuid();
      LOG.warnf("Case %s budget exhausted — transitioning to FAULTED", caseId);

      String oldStatus = instance.getState().name();
      instance.setState(CaseStatus.FAULTED);

      EventLog eventLog = new EventLog();
      eventLog.setEventType(CaseHubEventType.CASE_FAULTED);
      eventLog.setCaseId(caseId);
      eventLog.setStreamType(EventStreamType.CASE);
      eventLog.setTimestamp(now);
      eventLog.setMetadata(OBJECT_MAPPER.createObjectNode().put("reason", "timeout"));

      caseInstanceRepository
          .updateStateAndAppendEvent(instance, eventLog)
          .subscribe()
          .with(
              ignored ->
                  eventBus.publish(
                      EventBusAddresses.CASE_STATUS_CHANGED,
                      new CaseStatusChanged(instance, oldStatus, CaseStatus.FAULTED.name())),
              error ->
                  LOG.errorf(
                      error,
                      "Failed to persist FAULTED state for case %s — state is already mutated in cache",
                      caseId));
    }
  }
}
