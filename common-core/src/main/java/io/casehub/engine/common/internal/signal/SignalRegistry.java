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
package io.casehub.engine.common.internal.signal;

import io.casehub.api.model.signal.PerceivedSignal;
import io.casehub.api.model.signal.Signal;
import io.casehub.api.model.signal.SignalDecay;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SignalRegistry implements Resettable {

  private static final Logger LOG = Logger.getLogger(SignalRegistry.class);

  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Signal>> signals =
      new ConcurrentHashMap<>();

  @jakarta.inject.Inject
  jakarta.enterprise.inject.Instance<io.casehub.engine.common.internal.convergence.ActivityTracker>
      activityTrackerInstance;

  public boolean deposit(
      UUID caseId, String name, double strength, Duration halfLife, String source, int maxPerCase) {
    return deposit(caseId, name, strength, halfLife, source, maxPerCase, Instant.now());
  }

  public boolean deposit(
      UUID caseId,
      String name,
      double strength,
      Duration halfLife,
      String source,
      int maxPerCase,
      Instant now) {
    ConcurrentHashMap<String, Signal> caseSignals =
        signals.computeIfAbsent(caseId, k -> new ConcurrentHashMap<>());

    Signal existing = caseSignals.get(name);

    if (existing != null && !existing.expired()) {
      double currentEffective =
          SignalDecay.effectiveStrength(
              existing.strength(), existing.lastReinforced(), existing.halfLife(), now);
      double newStrength = Math.max(currentEffective, strength);
      Set<String> mergedSources = new HashSet<>(existing.sources());
      mergedSources.add(source);
      caseSignals.put(
          name,
          new Signal(
              name,
              newStrength,
              existing.firstDeposited(),
              now,
              halfLife,
              source,
              existing.reinforcementCount() + 1,
              false,
              Set.copyOf(mergedSources)));
      recordSignalDeposit(caseId);
      return true;
    }

    if (existing != null && existing.expired()) {
      caseSignals.put(
          name, new Signal(name, strength, now, now, halfLife, source, 1, false, Set.of(source)));
      recordSignalDeposit(caseId);
      return true;
    }

    if (caseSignals.size() >= maxPerCase) {
      LOG.warnf(
          "Signal cap reached for case=%s (max=%d), dropping signal=%s", caseId, maxPerCase, name);
      return false;
    }

    caseSignals.put(
        name, new Signal(name, strength, now, now, halfLife, source, 1, false, Set.of(source)));
    recordSignalDeposit(caseId);
    return true;
  }

  private void recordSignalDeposit(UUID caseId) {
    if (activityTrackerInstance != null && activityTrackerInstance.isResolvable()) {
      activityTrackerInstance.get().recordSignalDeposit(caseId);
    }
  }

  public Map<String, PerceivedSignal> perceive(UUID caseId, double effectiveZeroThreshold) {
    return perceive(caseId, effectiveZeroThreshold, Instant.now());
  }

  public Map<String, PerceivedSignal> perceive(
      UUID caseId, double effectiveZeroThreshold, Instant now) {
    ConcurrentHashMap<String, Signal> caseSignals = signals.get(caseId);
    if (caseSignals == null) return Map.of();

    Map<String, PerceivedSignal> result = new LinkedHashMap<>();
    for (Signal signal : caseSignals.values()) {
      if (signal.expired()) continue;
      double effective =
          SignalDecay.effectiveStrength(
              signal.strength(), signal.lastReinforced(), signal.halfLife(), now);
      if (effective < effectiveZeroThreshold) continue;
      result.put(
          signal.name(),
          new PerceivedSignal(
              signal.name(),
              effective,
              signal.reinforcementCount(),
              signal.lastSource(),
              Duration.between(signal.lastReinforced(), now)));
    }
    return Collections.unmodifiableMap(result);
  }

  public List<Signal> findNewlyExpired(UUID caseId, double effectiveZeroThreshold) {
    return findNewlyExpired(caseId, effectiveZeroThreshold, Instant.now());
  }

  public List<Signal> findNewlyExpired(UUID caseId, double effectiveZeroThreshold, Instant now) {
    ConcurrentHashMap<String, Signal> caseSignals = signals.get(caseId);
    if (caseSignals == null) return List.of();

    List<Signal> expired = new ArrayList<>();
    for (Signal signal : caseSignals.values()) {
      if (signal.expired()) continue;
      double effective =
          SignalDecay.effectiveStrength(
              signal.strength(), signal.lastReinforced(), signal.halfLife(), now);
      if (effective < effectiveZeroThreshold) {
        expired.add(signal);
      }
    }
    return expired;
  }

  public void markExpired(UUID caseId, String name) {
    ConcurrentHashMap<String, Signal> caseSignals = signals.get(caseId);
    if (caseSignals == null) return;
    Signal existing = caseSignals.get(name);
    if (existing == null) return;
    caseSignals.put(
        name,
        new Signal(
            existing.name(),
            existing.strength(),
            existing.firstDeposited(),
            existing.lastReinforced(),
            existing.halfLife(),
            existing.lastSource(),
            existing.reinforcementCount(),
            true,
            existing.sources()));
  }

  public Map<String, Signal> getAllSignals(UUID caseId) {
    ConcurrentHashMap<String, Signal> caseSignals = signals.get(caseId);
    return caseSignals == null ? Map.of() : Map.copyOf(caseSignals);
  }

  public Map<String, Signal> consensusSignals(
      UUID caseId, int minSources, double effectiveZeroThreshold) {
    return consensusSignals(caseId, minSources, effectiveZeroThreshold, Instant.now());
  }

  public Map<String, Signal> consensusSignals(
      UUID caseId, int minSources, double effectiveZeroThreshold, Instant now) {
    ConcurrentHashMap<String, Signal> caseSignals = signals.get(caseId);
    if (caseSignals == null) {
      return Map.of();
    }
    Map<String, Signal> result = new LinkedHashMap<>();
    for (Signal signal : caseSignals.values()) {
      if (signal.expired()) {
        continue;
      }
      double effective =
          SignalDecay.effectiveStrength(
              signal.strength(), signal.lastReinforced(), signal.halfLife(), now);
      if (effective < effectiveZeroThreshold) {
        continue;
      }
      if (signal.sources().size() >= minSources) {
        result.put(signal.name(), signal);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  public void evictByCase(UUID caseId) {
    signals.remove(caseId);
  }

  public int signalCount(UUID caseId) {
    ConcurrentHashMap<String, Signal> caseSignals = signals.get(caseId);
    return caseSignals == null ? 0 : caseSignals.size();
  }

  @Override
  public void reset() {
    signals.clear();
  }
}
