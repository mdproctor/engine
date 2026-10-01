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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.improvement.HealthPolicy;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.HealthScoreSnapshot;
import io.casehub.api.spi.improvement.CapabilityArea;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class HealthScoreTracker implements Resettable {

  private static final int MAX_HISTORY_SIZE = 1000;

  private final ConcurrentHashMap<UUID, Deque<HealthScoreSnapshot>> history =
      new ConcurrentHashMap<>();
  private final CapabilityAreaRegistry areaRegistry;

  public HealthScoreTracker(CapabilityAreaRegistry areaRegistry) {
    this.areaRegistry = areaRegistry;
  }

  public double computeScore(UUID caseId, String tenancyId, HealthPolicy policy) {
    var weights = policy.effectiveWeights();
    double weightedSum = 0.0;
    double totalWeight = 0.0;
    for (CapabilityArea area : areaRegistry.active()) {
      var assessment = area.assess(caseId, tenancyId);
      if (assessment.landscapePosition() == CapabilityAreaAssessment.LandscapePosition.ABSENT) {
        continue;
      }
      double weight = weights.getOrDefault(area.id(), 0.1);
      weightedSum += weight * assessment.healthScore();
      totalWeight += weight;
    }
    return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
  }

  public void refresh(UUID caseId, String tenancyId, HealthPolicy policy) {
    var weights = policy.effectiveWeights();
    Map<String, Double> components = new LinkedHashMap<>();
    double weightedSum = 0.0;
    double totalWeight = 0.0;
    for (CapabilityArea area : areaRegistry.active()) {
      var assessment = area.assess(caseId, tenancyId);
      if (assessment.landscapePosition() == CapabilityAreaAssessment.LandscapePosition.ABSENT) {
        continue;
      }
      double healthScore = assessment.healthScore();
      components.put(area.id(), healthScore);
      double weight = weights.getOrDefault(area.id(), 0.1);
      weightedSum += weight * healthScore;
      totalWeight += weight;
    }
    double score = totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    var snapshot = new HealthScoreSnapshot(score, Instant.now(), components);
    var deque = history.computeIfAbsent(caseId, k -> new ArrayDeque<>());
    deque.addLast(snapshot);
    while (deque.size() > MAX_HISTORY_SIZE) {
      deque.removeFirst();
    }
  }

  public HealthScoreSnapshot latestSnapshot(UUID caseId) {
    var deque = history.get(caseId);
    return (deque != null && !deque.isEmpty()) ? deque.peekLast() : null;
  }

  public double delta(UUID caseId, int windowMinutes) {
    var deque = history.get(caseId);
    if (deque == null || deque.isEmpty()) return 0.0;

    var current = deque.peekLast();
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(windowMinutes));

    HealthScoreSnapshot baseline = null;
    for (var snapshot : deque) {
      if (snapshot.timestamp().isBefore(cutoff) || snapshot.timestamp().equals(cutoff)) {
        baseline = snapshot;
      }
    }

    if (baseline == null) {
      baseline = deque.peekFirst();
    }

    return current.score() - baseline.score();
  }

  @Override
  public void reset() {
    history.clear();
  }
}
