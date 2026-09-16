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
package io.casehub.engine.internal.observation;

import io.casehub.api.engine.NeighborSpace;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.signal.Signal;
import io.casehub.api.model.signal.SignalConfig;
import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.Neighbor;
import io.casehub.api.spi.observation.NeighborRelation;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import java.util.*;
import java.util.stream.Collectors;

public class DefaultNeighborSpace implements NeighborSpace {

  private final UUID caseId;
  private final String tenancyId;
  private final String selfAgentId;
  private final String selfBindingName;
  private final PlanItemStore planItemStore;
  private final ObservationRegistry observationRegistry;
  private final SignalRegistry signalRegistry;
  private final CaseDefinitionRegistry definitionRegistry;
  private final CaseInstanceCache caseInstanceCache;
  private final SignalConfig signalConfig;

  public DefaultNeighborSpace(
      UUID caseId,
      String tenancyId,
      String selfAgentId,
      String selfBindingName,
      PlanItemStore planItemStore,
      ObservationRegistry observationRegistry,
      SignalRegistry signalRegistry,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache,
      SignalConfig signalConfig) {
    this.caseId = caseId;
    this.tenancyId = tenancyId;
    this.selfAgentId = selfAgentId;
    this.selfBindingName = selfBindingName;
    this.planItemStore = planItemStore;
    this.observationRegistry = observationRegistry;
    this.signalRegistry = signalRegistry;
    this.definitionRegistry = definitionRegistry;
    this.caseInstanceCache = caseInstanceCache;
    this.signalConfig = signalConfig;
  }

  @Override
  public List<Neighbor> active() {
    if (planItemStore == null) return List.of();
    List<PlanItemRecord> items = planItemStore.findByCaseId(caseId, tenancyId);
    Map<String, PlanItemRecord> byExecutor = new LinkedHashMap<>();
    for (PlanItemRecord item : items) {
      if (item.executorName() == null) continue;
      if (item.executorName().equals(selfAgentId)) continue;
      if (item.status() != TaskStatus.RUNNING && item.status() != TaskStatus.DISPATCHING) continue;
      byExecutor.putIfAbsent(item.executorName(), item);
    }
    return byExecutor.entrySet().stream()
        .map(
            e ->
                new Neighbor(
                    e.getKey(),
                    Set.of(),
                    e.getValue().status(),
                    e.getValue().bindingName(),
                    Set.of(NeighborRelation.COACTIVE)))
        .toList();
  }

  @Override
  public List<Neighbor> withSharedInterests() {
    Map<String, List<EnvironmentObserver>> allObservers = observationRegistry.getObservers(caseId);
    List<EnvironmentObserver> myObservers = allObservers.getOrDefault(selfAgentId, List.of());
    if (myObservers.isEmpty()) return List.of();

    Set<String> myKeys =
        myObservers.stream().flatMap(o -> o.watchedKeys().stream()).collect(Collectors.toSet());
    if (myKeys.isEmpty()) return List.of();

    List<Neighbor> result = new ArrayList<>();
    for (var entry : allObservers.entrySet()) {
      if (entry.getKey().equals(selfAgentId)) continue;
      Set<String> theirKeys =
          entry.getValue().stream()
              .flatMap(o -> o.watchedKeys().stream())
              .collect(Collectors.toSet());
      if (!Collections.disjoint(myKeys, theirKeys)) {
        result.add(
            new Neighbor(
                entry.getKey(), Set.of(), null, null, Set.of(NeighborRelation.SHARED_INTEREST)));
      }
    }
    return result;
  }

  @Override
  public List<Neighbor> withSharedSignals() {
    double threshold = signalConfig != null ? signalConfig.effectiveZeroThreshold() : 0.01;
    Map<String, Signal> allSignals = signalRegistry.getAllSignals(caseId);

    Set<String> sharedAgents = new LinkedHashSet<>();
    for (Signal signal : allSignals.values()) {
      if (signal.expired()) continue;
      if (!signal.sources().contains(selfAgentId)) continue;
      for (String source : signal.sources()) {
        if (!source.equals(selfAgentId)) {
          sharedAgents.add(source);
        }
      }
    }

    return sharedAgents.stream()
        .map(
            agentId ->
                new Neighbor(agentId, Set.of(), null, null, Set.of(NeighborRelation.SHARED_SIGNAL)))
        .toList();
  }

  @Override
  public List<Neighbor> complementary() {
    if (definitionRegistry == null || caseInstanceCache == null || planItemStore == null) {
      return List.of();
    }
    var instance = caseInstanceCache.get(caseId);
    if (instance == null) return List.of();
    var definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) return List.of();

    var myBinding =
        definition.getBindings().stream()
            .filter(b -> selfBindingName != null && selfBindingName.equals(b.getName()))
            .findFirst()
            .orElse(null);
    Set<String> myProducedKeys =
        myBinding != null && myBinding.getProducedKeys() != null
            ? myBinding.getProducedKeys()
            : Set.of();

    Map<String, List<EnvironmentObserver>> allObservers = observationRegistry.getObservers(caseId);
    Set<String> myWatchedKeys =
        allObservers.getOrDefault(selfAgentId, List.of()).stream()
            .flatMap(o -> o.watchedKeys().stream())
            .collect(Collectors.toSet());

    List<PlanItemRecord> activeItems = planItemStore.findByCaseId(caseId, tenancyId);
    Set<String> result = new LinkedHashSet<>();

    for (PlanItemRecord item : activeItems) {
      if (item.executorName() == null || item.executorName().equals(selfAgentId)) continue;
      var theirBinding =
          definition.getBindings().stream()
              .filter(b -> item.bindingName() != null && item.bindingName().equals(b.getName()))
              .findFirst()
              .orElse(null);
      Set<String> theirProducedKeys =
          theirBinding != null && theirBinding.getProducedKeys() != null
              ? theirBinding.getProducedKeys()
              : Set.of();
      Set<String> theirWatchedKeys =
          allObservers.getOrDefault(item.executorName(), List.of()).stream()
              .flatMap(o -> o.watchedKeys().stream())
              .collect(Collectors.toSet());

      boolean iProduceTheyWatch =
          !myProducedKeys.isEmpty()
              && !theirWatchedKeys.isEmpty()
              && !Collections.disjoint(myProducedKeys, theirWatchedKeys);
      boolean theyProduceIWatch =
          !theirProducedKeys.isEmpty()
              && !myWatchedKeys.isEmpty()
              && !Collections.disjoint(theirProducedKeys, myWatchedKeys);
      if (iProduceTheyWatch || theyProduceIWatch) {
        result.add(item.executorName());
      }
    }

    return result.stream()
        .map(
            agentId ->
                new Neighbor(agentId, Set.of(), null, null, Set.of(NeighborRelation.COMPLEMENTARY)))
        .toList();
  }
}
