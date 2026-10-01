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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.model.improvement.ImprovementOutcome;
import io.casehub.api.model.stigmergy.SummaryScope;
import io.casehub.api.view.EvolutionSummary;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultSummarizationProviderTest {

  private TestEventLogRepo eventLogRepo;
  private ImprovementCategoryTracker categoryTracker;
  private DefaultSummarizationProvider provider;
  private UUID caseId;
  private static final String TENANT = "t1";

  @BeforeEach
  void setUp() {
    eventLogRepo = new TestEventLogRepo();
    categoryTracker = new ImprovementCategoryTracker();
    provider = new DefaultSummarizationProvider(eventLogRepo, categoryTracker);
    caseId = UUID.randomUUID();
  }

  // --- Outcome counting ---

  @Test
  void noEventsReturnsZeroCounts() {
    var scope = new SummaryScope(null, null, null, null);

    var summary = provider.summarize(caseId, TENANT, scope);

    assertThat(summary.scope()).isEqualTo(scope);
    assertThat(summary.computedAt()).isNotNull();
    assertThat(summary.totalImprovements()).isZero();
    assertThat(summary.successCount()).isZero();
    assertThat(summary.failureCount()).isZero();
    assertThat(summary.rejectionCount()).isZero();
    assertThat(summary.regressionCount()).isZero();
    assertThat(summary.rollbackCount()).isZero();
    assertThat(summary.categories()).isEmpty();
    assertThat(summary.notableEvents()).isEmpty();
  }

  @Test
  void countsOutcomesByStatus() {
    appendOutcome("dep-update", "pom.xml", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome(
        "dep-update", "build.gradle", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome(
        "code-quality", "Main.java", ImprovementOutcome.OutcomeStatus.FAILED, Instant.now());
    appendOutcome(
        "code-quality", "Util.java", ImprovementOutcome.OutcomeStatus.REJECTED, Instant.now());
    appendOutcome(
        "test-coverage",
        "FooTest.java",
        ImprovementOutcome.OutcomeStatus.REGRESSION,
        Instant.now());
    appendOutcome(
        "test-coverage", "BarTest.java", ImprovementOutcome.OutcomeStatus.ABANDONED, Instant.now());

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.totalImprovements()).isEqualTo(6);
    assertThat(summary.successCount()).isEqualTo(2);
    assertThat(summary.failureCount()).isEqualTo(1);
    assertThat(summary.rejectionCount()).isEqualTo(1);
    assertThat(summary.regressionCount()).isEqualTo(1);
    assertThat(summary.rollbackCount()).isEqualTo(1);
  }

  // --- Scope filtering ---

  @Test
  void categoryFilterOnlyCountsMatchingCategory() {
    appendOutcome("dep-update", "pom.xml", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome(
        "code-quality", "Main.java", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome(
        "dep-update", "build.gradle", ImprovementOutcome.OutcomeStatus.FAILED, Instant.now());

    var summary =
        provider.summarize(caseId, TENANT, new SummaryScope(null, "dep-update", null, null));

    assertThat(summary.totalImprovements()).isEqualTo(2);
    assertThat(summary.successCount()).isEqualTo(1);
    assertThat(summary.failureCount()).isEqualTo(1);
  }

  @Test
  void timeWindowFilterExcludesOldEvents() {
    appendOutcome(
        "dep-update",
        "old.xml",
        ImprovementOutcome.OutcomeStatus.MERGED,
        Instant.now().minusSeconds(7200));
    appendOutcome(
        "dep-update",
        "recent.xml",
        ImprovementOutcome.OutcomeStatus.MERGED,
        Instant.now().minusSeconds(1800));

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, 60, null));

    assertThat(summary.totalImprovements()).isEqualTo(1);
    assertThat(summary.successCount()).isEqualTo(1);
  }

  @Test
  void improvementCaseIdFilterOnlyCountsMatching() {
    UUID targetImprovement = UUID.randomUUID();
    appendOutcomeWithImprovementId(
        "dep-update",
        "pom.xml",
        ImprovementOutcome.OutcomeStatus.MERGED,
        Instant.now(),
        targetImprovement);
    appendOutcome(
        "dep-update", "other.xml", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());

    var summary =
        provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, targetImprovement));

    assertThat(summary.totalImprovements()).isEqualTo(1);
    assertThat(summary.successCount()).isEqualTo(1);
  }

  // --- Per-category breakdown ---

  @Test
  void categorySummaryComputesSuccessRate() {
    appendOutcome("dep-update", "a", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome("dep-update", "b", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome("dep-update", "c", ImprovementOutcome.OutcomeStatus.FAILED, Instant.now());
    appendOutcome("code-quality", "d", ImprovementOutcome.OutcomeStatus.REJECTED, Instant.now());

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.categories()).hasSize(2);

    var depUpdate =
        summary.categories().stream()
            .filter(c -> c.category().equals("dep-update"))
            .findFirst()
            .orElseThrow();
    assertThat(depUpdate.totalOutcomes()).isEqualTo(3);
    assertThat(depUpdate.successRate()).isCloseTo(2.0 / 3.0, withinPercentage(1));
    assertThat(depUpdate.suppressed()).isFalse();
    assertThat(depUpdate.paused()).isFalse();

    var codeQuality =
        summary.categories().stream()
            .filter(c -> c.category().equals("code-quality"))
            .findFirst()
            .orElseThrow();
    assertThat(codeQuality.totalOutcomes()).isEqualTo(1);
    assertThat(codeQuality.successRate()).isZero();
  }

  @Test
  void categorySummaryReflectsSuppression() {
    appendOutcome("flaky-cat", "a", ImprovementOutcome.OutcomeStatus.FAILED, Instant.now());
    categoryTracker.recordOutcome(caseId, "flaky-cat", ImprovementOutcome.OutcomeStatus.FAILED);
    categoryTracker.recordOutcome(caseId, "flaky-cat", ImprovementOutcome.OutcomeStatus.FAILED);
    categoryTracker.recordOutcome(caseId, "flaky-cat", ImprovementOutcome.OutcomeStatus.FAILED);

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    var cat =
        summary.categories().stream()
            .filter(c -> c.category().equals("flaky-cat"))
            .findFirst()
            .orElseThrow();
    assertThat(cat.suppressed()).isTrue();
  }

  @Test
  void categorySummaryReflectsPause() {
    appendOutcome("paused-cat", "a", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    categoryTracker.pauseCategory(caseId, "paused-cat", java.time.Duration.ofHours(1));

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    var cat =
        summary.categories().stream()
            .filter(c -> c.category().equals("paused-cat"))
            .findFirst()
            .orElseThrow();
    assertThat(cat.paused()).isTrue();
  }

  // --- Notable events ---

  @Test
  void circuitBreakerEventsAppearAsNotable() {
    appendNotableEvent(
        CaseHubEventType.CIRCUIT_BREAKER_TRIPPED, Instant.now(), Map.of("area", "stability"));

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.notableEvents()).hasSize(1);
    assertThat(summary.notableEvents().get(0).type())
        .isEqualTo(CaseHubEventType.CIRCUIT_BREAKER_TRIPPED);
  }

  @Test
  void regressionDetectedAppearAsNotable() {
    appendNotableEvent(
        CaseHubEventType.REGRESSION_DETECTED, Instant.now(), Map.of("category", "dep-update"));

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.notableEvents()).hasSize(1);
    assertThat(summary.notableEvents().get(0).type())
        .isEqualTo(CaseHubEventType.REGRESSION_DETECTED);
  }

  @Test
  void notableEventsFilteredByTimeWindow() {
    appendNotableEvent(
        CaseHubEventType.CIRCUIT_BREAKER_TRIPPED, Instant.now().minusSeconds(7200), Map.of());
    appendNotableEvent(
        CaseHubEventType.REGRESSION_DETECTED, Instant.now().minusSeconds(1800), Map.of());

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, 60, null));

    assertThat(summary.notableEvents()).hasSize(1);
    assertThat(summary.notableEvents().get(0).type())
        .isEqualTo(CaseHubEventType.REGRESSION_DETECTED);
  }

  // --- Trends ---

  @Test
  void successRateTrendComparesRecentVsOlder() {
    Instant now = Instant.now();
    // Older half: 1 success, 1 failure → 50% success rate
    appendOutcome("cat", "a", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(3000));
    appendOutcome("cat", "b", ImprovementOutcome.OutcomeStatus.FAILED, now.minusSeconds(2700));
    // Recent half: 2 successes → 100% success rate
    appendOutcome("cat", "c", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(900));
    appendOutcome("cat", "d", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(600));

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, 60, null));

    assertThat(summary.successRateTrend()).isNotNull();
    assertThat(summary.successRateTrend()).isGreaterThan(0.0);
  }

  @Test
  void singleOutcomeProducesNullTrend() {
    appendOutcome("cat", "a", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.successRateTrend()).isNull();
  }

  // --- Tenant isolation ---

  @Test
  void eventsFromOtherTenantExcluded() {
    appendOutcome("dep-update", "a", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcomeForTenant(
        "dep-update", "b", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now(), "other-tenant");

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.totalImprovements()).isEqualTo(1);
  }

  // --- Helpers ---

  // --- Combined scope filters ---

  @Test
  void categoryAndTimeWindowFiltersCombine() {
    Instant now = Instant.now();
    appendOutcome(
        "dep-update", "old", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(7200));
    appendOutcome(
        "dep-update", "recent", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(1800));
    appendOutcome(
        "code-quality", "recent2", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(1800));

    var summary =
        provider.summarize(caseId, TENANT, new SummaryScope(null, "dep-update", 60, null));

    assertThat(summary.totalImprovements()).isEqualTo(1);
    assertThat(summary.successCount()).isEqualTo(1);
  }

  // --- Edge cases ---

  @Test
  void eventWithNullPayloadIsSkipped() {
    EventLog event = new EventLog();
    event.setCaseId(caseId);
    event.setEventType(CaseHubEventType.IMPROVEMENT_OUTCOME);
    event.setTimestamp(Instant.now());
    event.setPayload(null);
    eventLogRepo.append(event, TENANT);

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.totalImprovements()).isZero();
  }

  @Test
  void allMergedOutcomesGiveFullSuccessRate() {
    appendOutcome("cat", "a", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome("cat", "b", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    var cat =
        summary.categories().stream()
            .filter(c -> c.category().equals("cat"))
            .findFirst()
            .orElseThrow();
    assertThat(cat.successRate()).isEqualTo(1.0);
  }

  @Test
  void notableEventPayloadFieldsExtracted() {
    appendNotableEvent(
        CaseHubEventType.CIRCUIT_BREAKER_TRIPPED,
        Instant.now(),
        Map.of("area", "stability", "reason", "3 consecutive failures"));

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    var notable = summary.notableEvents().get(0);
    assertThat(notable.summary()).containsEntry("area", "stability");
    assertThat(notable.summary()).containsEntry("reason", "3 consecutive failures");
  }

  @Test
  void researchDirectionsEmptyWithNoGoalEvents() {
    appendOutcome("dep-update", "a", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.researchDirections()).isEmpty();
  }

  @Test
  void categoriesSortedAlphabetically() {
    appendOutcome("zebra", "a", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome("alpha", "b", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());
    appendOutcome("middle", "c", ImprovementOutcome.OutcomeStatus.MERGED, Instant.now());

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, null, null));

    assertThat(summary.categories())
        .extracting(EvolutionSummary.CategorySummary::category)
        .containsExactly("alpha", "middle", "zebra");
  }

  @Test
  void trendWithEqualHalvesReturnsZeroDelta() {
    Instant now = Instant.now();
    appendOutcome("cat", "a", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(3000));
    appendOutcome("cat", "b", ImprovementOutcome.OutcomeStatus.MERGED, now.minusSeconds(600));

    var summary = provider.summarize(caseId, TENANT, new SummaryScope(null, null, 60, null));

    assertThat(summary.successRateTrend()).isEqualTo(0.0);
  }

  private void appendOutcome(
      String category, String target, ImprovementOutcome.OutcomeStatus status, Instant timestamp) {
    appendOutcomeWithImprovementId(category, target, status, timestamp, UUID.randomUUID());
  }

  private void appendOutcomeWithImprovementId(
      String category,
      String target,
      ImprovementOutcome.OutcomeStatus status,
      Instant timestamp,
      UUID improvementCaseId) {
    ObjectNode payload = new ObjectMapper().createObjectNode();
    payload.put("category", category);
    payload.put("target", target);
    payload.put("status", status.name());
    payload.put("improvementCaseId", improvementCaseId.toString());

    EventLog event = new EventLog();
    event.setCaseId(caseId);
    event.setEventType(CaseHubEventType.IMPROVEMENT_OUTCOME);
    event.setTimestamp(timestamp);
    event.setPayload(payload);
    eventLogRepo.append(event, TENANT);
  }

  private void appendOutcomeForTenant(
      String category,
      String target,
      ImprovementOutcome.OutcomeStatus status,
      Instant timestamp,
      String tenancyId) {
    ObjectNode payload = new ObjectMapper().createObjectNode();
    payload.put("category", category);
    payload.put("target", target);
    payload.put("status", status.name());
    payload.put("improvementCaseId", UUID.randomUUID().toString());

    EventLog event = new EventLog();
    event.setCaseId(caseId);
    event.setEventType(CaseHubEventType.IMPROVEMENT_OUTCOME);
    event.setTimestamp(timestamp);
    event.setPayload(payload);
    eventLogRepo.append(event, tenancyId);
  }

  private void appendNotableEvent(
      CaseHubEventType type, Instant timestamp, Map<String, String> summaryFields) {
    ObjectNode payload = new ObjectMapper().createObjectNode();
    summaryFields.forEach(payload::put);

    EventLog event = new EventLog();
    event.setCaseId(caseId);
    event.setEventType(type);
    event.setTimestamp(timestamp);
    event.setPayload(payload);
    eventLogRepo.append(event, TENANT);
  }

  static class TestEventLogRepo implements EventLogRepository {
    final List<EventLog> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(EventLog eventLog, String tenancyId) {
      eventLog.tenancyId = tenancyId;
      if (eventLog.getTimestamp() == null) {
        eventLog.setTimestamp(Instant.now());
      }
      entries.add(eventLog);
    }

    @Override
    public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
      append(eventLog, tenancyId);
      return (long) entries.size();
    }

    @Override
    public EventLog findById(Long id, String tenancyId) {
      return null;
    }

    @Override
    public List<EventLog> findSchedulingEvents(
        UUID caseId, String workerId, Instant after, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByCaseAndTypes(
        UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
      return entries.stream()
          .filter(
              e ->
                  e.getCaseId().equals(caseId)
                      && types.contains(e.getEventType())
                      && tenancyId.equals(e.tenancyId))
          .toList();
    }

    @Override
    public List<EventLog> findByCaseAndWorkerAndType(
        UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByWorkerAndType(
        String workerId, CaseHubEventType type, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByCaseWithFilters(
        UUID caseId,
        Collection<CaseHubEventType> eventTypes,
        Collection<EventStreamType> streamTypes,
        String tenancyId) {
      return List.of();
    }
  }
}
