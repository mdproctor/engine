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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.improvement.ImprovementOutcome;
import io.casehub.api.model.stigmergy.SummaryScope;
import io.casehub.api.spi.improvement.SummarizationProvider;
import io.casehub.api.view.EvolutionSummary;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@DefaultBean
@ApplicationScoped
public class DefaultSummarizationProvider implements SummarizationProvider {

  private static final Set<CaseHubEventType> OUTCOME_TYPES =
      Set.of(CaseHubEventType.IMPROVEMENT_OUTCOME);

  private static final Set<CaseHubEventType> NOTABLE_TYPES =
      Set.of(
          CaseHubEventType.CIRCUIT_BREAKER_TRIPPED,
          CaseHubEventType.CIRCUIT_BREAKER_RECOVERING,
          CaseHubEventType.CIRCUIT_BREAKER_RESET,
          CaseHubEventType.REGRESSION_DETECTED,
          CaseHubEventType.ROLLBACK_STARTED,
          CaseHubEventType.COMPLIANCE_LEVEL_CHANGED);

  private static final Set<CaseHubEventType> ALL_TYPES;

  static {
    var all = new HashSet<>(OUTCOME_TYPES);
    all.addAll(NOTABLE_TYPES);
    ALL_TYPES = Set.copyOf(all);
  }

  private final EventLogRepository eventLogRepository;
  private final ImprovementCategoryTracker categoryTracker;

  @Inject
  public DefaultSummarizationProvider(
      EventLogRepository eventLogRepository, ImprovementCategoryTracker categoryTracker) {
    this.eventLogRepository = eventLogRepository;
    this.categoryTracker = categoryTracker;
  }

  @Override
  public EvolutionSummary summarize(UUID caseId, String tenancyId, SummaryScope scope) {
    List<EventLog> allEvents = eventLogRepository.findByCaseAndTypes(caseId, ALL_TYPES, tenancyId);
    Instant cutoff =
        scope.timeWindowMinutes() != null
            ? Instant.now().minus(Duration.ofMinutes(scope.timeWindowMinutes()))
            : Instant.MIN;

    List<EventLog> filtered =
        allEvents.stream()
            .filter(e -> e.getTimestamp() != null && !e.getTimestamp().isBefore(cutoff))
            .toList();

    List<EventLog> outcomes =
        filtered.stream()
            .filter(e -> OUTCOME_TYPES.contains(e.getEventType()))
            .filter(e -> matchesScope(e, scope))
            .toList();

    int successCount = countByStatus(outcomes, ImprovementOutcome.OutcomeStatus.MERGED);
    int failureCount = countByStatus(outcomes, ImprovementOutcome.OutcomeStatus.FAILED);
    int rejectionCount = countByStatus(outcomes, ImprovementOutcome.OutcomeStatus.REJECTED);
    int regressionCount = countByStatus(outcomes, ImprovementOutcome.OutcomeStatus.REGRESSION);
    int rollbackCount = countByStatus(outcomes, ImprovementOutcome.OutcomeStatus.ABANDONED);

    List<EvolutionSummary.CategorySummary> categories = buildCategorySummaries(caseId, outcomes);
    List<EvolutionSummary.NotableEvent> notableEvents = buildNotableEvents(filtered);
    Double successRateTrend = computeSuccessRateTrend(outcomes);

    return new EvolutionSummary(
        scope,
        Instant.now(),
        outcomes.size(),
        successCount,
        failureCount,
        rejectionCount,
        regressionCount,
        rollbackCount,
        null,
        successRateTrend,
        categories,
        List.of(),
        notableEvents);
  }

  private boolean matchesScope(EventLog event, SummaryScope scope) {
    JsonNode payload = event.getPayload();
    if (payload == null) {
      return false;
    }

    if (scope.category() != null) {
      JsonNode cat = payload.get("category");
      if (cat == null || !scope.category().equals(cat.asText())) {
        return false;
      }
    }

    if (scope.improvementCaseId() != null) {
      JsonNode id = payload.get("improvementCaseId");
      if (id == null || !scope.improvementCaseId().toString().equals(id.asText())) {
        return false;
      }
    }

    return true;
  }

  private int countByStatus(List<EventLog> outcomes, ImprovementOutcome.OutcomeStatus status) {
    return (int)
        outcomes.stream()
            .filter(
                e -> {
                  JsonNode payload = e.getPayload();
                  return payload != null
                      && payload.has("status")
                      && status.name().equals(payload.get("status").asText());
                })
            .count();
  }

  private List<EvolutionSummary.CategorySummary> buildCategorySummaries(
      UUID caseId, List<EventLog> outcomes) {
    Map<String, List<EventLog>> byCategory =
        outcomes.stream()
            .filter(e -> e.getPayload() != null && e.getPayload().has("category"))
            .collect(Collectors.groupingBy(e -> e.getPayload().get("category").asText()));

    return byCategory.entrySet().stream()
        .map(
            entry -> {
              String category = entry.getKey();
              List<EventLog> events = entry.getValue();
              int total = events.size();
              int successes = countByStatus(events, ImprovementOutcome.OutcomeStatus.MERGED);
              double successRate = total > 0 ? (double) successes / total : 0.0;
              boolean suppressed = categoryTracker.isSuppressed(caseId, category);
              var state = categoryTracker.states(caseId).get(category);
              boolean paused = state != null && state.paused();
              return new EvolutionSummary.CategorySummary(
                  category, total, successRate, suppressed, paused);
            })
        .sorted(Comparator.comparing(EvolutionSummary.CategorySummary::category))
        .toList();
  }

  private List<EvolutionSummary.NotableEvent> buildNotableEvents(List<EventLog> filtered) {
    return filtered.stream()
        .filter(e -> NOTABLE_TYPES.contains(e.getEventType()))
        .map(
            e -> {
              Map<String, String> summary = new LinkedHashMap<>();
              JsonNode payload = e.getPayload();
              if (payload != null && payload.isObject()) {
                payload
                    .fields()
                    .forEachRemaining(f -> summary.put(f.getKey(), f.getValue().asText()));
              }
              return new EvolutionSummary.NotableEvent(e.getEventType(), e.getTimestamp(), summary);
            })
        .sorted(Comparator.comparing(EvolutionSummary.NotableEvent::timestamp))
        .toList();
  }

  private Double computeSuccessRateTrend(List<EventLog> outcomes) {
    if (outcomes.size() < 2) {
      return null;
    }

    List<EventLog> sorted =
        outcomes.stream().sorted(Comparator.comparing(EventLog::getTimestamp)).toList();

    int midpoint = sorted.size() / 2;
    List<EventLog> olderHalf = sorted.subList(0, midpoint);
    List<EventLog> recentHalf = sorted.subList(midpoint, sorted.size());

    double olderRate = successRate(olderHalf);
    double recentRate = successRate(recentHalf);
    return recentRate - olderRate;
  }

  private double successRate(List<EventLog> events) {
    if (events.isEmpty()) {
      return 0.0;
    }
    int successes = countByStatus(events, ImprovementOutcome.OutcomeStatus.MERGED);
    return (double) successes / events.size();
  }
}
