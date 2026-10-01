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
package io.casehub.engine.runtime.improvement;

import io.casehub.api.model.improvement.HealthPolicy;
import io.casehub.api.model.stigmergy.CircuitBreakerState;
import io.casehub.engine.common.spi.Resettable;
import io.casehub.engine.common.spi.event.CircuitBreakerStateChangedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ImprovementCircuitBreaker implements Resettable {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final ConcurrentHashMap<UUID, CircuitBreakerState> states = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Integer> halfOpenCount = new ConcurrentHashMap<>();
  private final Event<CircuitBreakerStateChangedEvent> stateChangedEvent;
  private final io.casehub.engine.common.spi.EventLogRepository eventLogRepository;

  @Inject
  public ImprovementCircuitBreaker(
      Event<CircuitBreakerStateChangedEvent> stateChangedEvent,
      jakarta.enterprise.inject.Instance<io.casehub.engine.common.spi.EventLogRepository>
          eventLogRepo) {
    this.stateChangedEvent = stateChangedEvent;
    this.eventLogRepository = eventLogRepo.isResolvable() ? eventLogRepo.get() : null;
  }

  ImprovementCircuitBreaker(Event<CircuitBreakerStateChangedEvent> stateChangedEvent) {
    this.stateChangedEvent = stateChangedEvent;
    this.eventLogRepository = null;
  }

  public CircuitBreakerState state(UUID caseId) {
    return states.getOrDefault(caseId, CircuitBreakerState.CLOSED);
  }

  public void evaluate(
      UUID caseId, String tenancyId, HealthScoreTracker tracker, HealthPolicy policy) {
    double score = tracker.computeScore(caseId, tenancyId, policy);
    double delta = tracker.delta(caseId, policy.effectiveHealthWindowMinutes());
    var current = state(caseId);
    CircuitBreakerState newState = current;

    switch (current) {
      case CLOSED -> {
        if (score < policy.effectiveHealthThreshold()
            || delta < -policy.effectiveHealthDeltaThreshold()) {
          newState = CircuitBreakerState.OPEN;
          states.put(caseId, newState);
        }
      }
      case OPEN -> {
        if (score >= policy.effectiveHealthThreshold()) {
          newState = CircuitBreakerState.HALF_OPEN;
          states.put(caseId, newState);
          halfOpenCount.put(caseId, 0);
        }
      }
      case HALF_OPEN -> {
        int completed = halfOpenCount.getOrDefault(caseId, 0);
        if (score < policy.effectiveHealthThreshold()) {
          newState = CircuitBreakerState.OPEN;
          states.put(caseId, newState);
        } else if (completed >= policy.effectiveHalfOpenMaxImprovements()) {
          newState = CircuitBreakerState.CLOSED;
          states.put(caseId, newState);
          halfOpenCount.remove(caseId);
        }
      }
    }

    if (newState != current) {
      stateChangedEvent.fireAsync(new CircuitBreakerStateChangedEvent(caseId, current, newState));
      emitEventLog(caseId, tenancyId, current, newState);
    }
  }

  public void recordImprovementInHalfOpen(UUID caseId) {
    halfOpenCount.computeIfPresent(caseId, (k, v) -> v + 1);
  }

  public void manualReset(UUID caseId, String tenancyId) {
    var previous = states.put(caseId, CircuitBreakerState.CLOSED);
    halfOpenCount.remove(caseId);
    if (previous != null && previous != CircuitBreakerState.CLOSED) {
      stateChangedEvent.fireAsync(
          new CircuitBreakerStateChangedEvent(caseId, previous, CircuitBreakerState.CLOSED));
      emitEventLog(caseId, tenancyId, previous, CircuitBreakerState.CLOSED);
    }
  }

  @Deprecated(forRemoval = true)
  public void manualReset(UUID caseId) {
    manualReset(caseId, null);
  }

  public void restoreFromEventLog(UUID caseId, String tenancyId) {
    if (eventLogRepository == null) {
      return;
    }
    var events =
        eventLogRepository.findByCaseAndTypes(
            caseId,
            java.util.List.of(
                io.casehub.api.model.event.CaseHubEventType.CIRCUIT_BREAKER_TRIPPED,
                io.casehub.api.model.event.CaseHubEventType.CIRCUIT_BREAKER_RECOVERING,
                io.casehub.api.model.event.CaseHubEventType.CIRCUIT_BREAKER_RESET),
            tenancyId);
    if (events.isEmpty()) {
      return;
    }

    var last = events.get(events.size() - 1);
    switch (last.getEventType()) {
      case CIRCUIT_BREAKER_TRIPPED -> states.put(caseId, CircuitBreakerState.OPEN);
      case CIRCUIT_BREAKER_RECOVERING -> {
        states.put(caseId, CircuitBreakerState.HALF_OPEN);
        int count = 0;
        if (last.getMetadata() != null && last.getMetadata().has("halfOpenCount")) {
          count = last.getMetadata().get("halfOpenCount").asInt();
        }
        halfOpenCount.put(caseId, count);
      }
      case CIRCUIT_BREAKER_RESET -> {
        states.remove(caseId);
        halfOpenCount.remove(caseId);
      }
      default -> {}
    }
  }

  private void emitEventLog(
      UUID caseId, String tenancyId, CircuitBreakerState from, CircuitBreakerState to) {
    if (eventLogRepository == null || tenancyId == null) {
      return;
    }
    var eventType =
        switch (to) {
          case OPEN -> io.casehub.api.model.event.CaseHubEventType.CIRCUIT_BREAKER_TRIPPED;
          case HALF_OPEN -> io.casehub.api.model.event.CaseHubEventType.CIRCUIT_BREAKER_RECOVERING;
          case CLOSED -> io.casehub.api.model.event.CaseHubEventType.CIRCUIT_BREAKER_RESET;
        };
    var log = new io.casehub.engine.common.internal.history.EventLog();
    log.setCaseId(caseId);
    log.setEventType(eventType);
    log.setStreamType(io.casehub.api.model.event.EventStreamType.CASE);
    log.setTimestamp(java.time.Instant.now());
    var meta = MAPPER.createObjectNode();
    meta.put("from", from.name());
    meta.put("to", to.name());
    if (to == CircuitBreakerState.HALF_OPEN) {
      meta.put("halfOpenCount", halfOpenCount.getOrDefault(caseId, 0));
    }
    log.setMetadata(meta);
    eventLogRepository.append(log, tenancyId);
  }

  @Override
  public void reset() {
    states.clear();
    halfOpenCount.clear();
  }
}
