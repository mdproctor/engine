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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.improvement.HealthPolicy;
import io.casehub.api.model.improvement.ImprovementOutcome;
import io.casehub.api.model.improvement.RollbackPolicy;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.spi.improvement.CapabilityArea;
import io.casehub.api.spi.improvement.ImprovementCategoryProvider;
import io.casehub.engine.common.spi.event.RegressionDetectedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegressionDetectorTest {

  private RegressionDetector detector;
  private RegressionEvaluatorRegistry evaluatorRegistry;
  private ImprovementCategoryRegistry improvementCategoryRegistry;

  private ImprovementCategoryTracker categoryTracker;
  private RollbackHistory rollbackHistory;
  private HealthScoreTracker healthTracker;
  private CapabilityAreaRegistry registry;
  private UUID caseId;
  private TestEvent<RegressionDetectedEvent> regressionDetectedEvents;

  @BeforeEach
  void setUp() {
    evaluatorRegistry = new RegressionEvaluatorRegistry();
    improvementCategoryRegistry = new ImprovementCategoryRegistry();
    improvementCategoryRegistry.registerProvider(
        new ImprovementCategoryProvider() {
          @Override
          public String domainId() {
            return "code-evolution";
          }

          @Override
          public List<CategoryDescriptor> categories() {
            return List.of(
                new CategoryDescriptor("lint-fix", "Lint Fix", "test", "code-evolution"));
          }

          @Override
          public List<StageDescriptor> stages() {
            return List.of();
          }
        });
    evaluatorRegistry.register(new HealthScoreDeltaRegressionEvaluator(new ConfidenceScorer()));
    categoryTracker = new ImprovementCategoryTracker();
    rollbackHistory = new RollbackHistory();
    registry = new CapabilityAreaRegistry();
    healthTracker = new HealthScoreTracker(registry);
    regressionDetectedEvents = new TestEvent<>();
    detector =
        new RegressionDetector(
            evaluatorRegistry,
            improvementCategoryRegistry,
            categoryTracker,
            rollbackHistory,
            healthTracker,
            regressionDetectedEvents);
    caseId = UUID.randomUUID();
  }

  @Test
  void mergedOutcomeStartsMonitoring() {
    registry.register(area("stability", 0.8));
    var policy = new HealthPolicy(null, null, null, null, null, null);
    healthTracker.refresh(caseId, "test-tenant", policy);

    var outcome = outcome(ImprovementOutcome.OutcomeStatus.MERGED);
    detector.onOutcome(caseId, outcome);

    assertThat(detector.activeMonitorCount(caseId)).isEqualTo(1);
  }

  @Test
  void nonMergedOutcomeIgnored() {
    var outcome = outcome(ImprovementOutcome.OutcomeStatus.FAILED);
    detector.onOutcome(caseId, outcome);

    assertThat(detector.activeMonitorCount(caseId)).isEqualTo(0);
  }

  @Test
  void noRegressionWhenHealthStable() {
    registry.register(area("stability", 0.8));
    var healthPolicy = new HealthPolicy(null, null, null, null, null, null);
    healthTracker.refresh(caseId, "test-tenant", healthPolicy);

    var outcome = outcome(ImprovementOutcome.OutcomeStatus.MERGED);
    detector.onOutcome(caseId, outcome);

    var rollbackPolicy = new RollbackPolicy(null, null, null, null, null, null);
    detector.checkActiveMonitors(caseId, healthTracker, rollbackPolicy);

    assertThat(categoryTracker.isSuppressed(caseId, "lint-fix")).isFalse();
  }

  @Test
  void regressionDetectedWhenHealthDrops() {
    registry.register(area("stability", 0.8));
    var healthPolicy = new HealthPolicy(null, null, null, null, null, null);
    healthTracker.refresh(caseId, "test-tenant", healthPolicy);

    var outcome = outcome(ImprovementOutcome.OutcomeStatus.MERGED);
    detector.onOutcome(caseId, outcome);

    registry.deprecate("stability");
    registry.register(area("stability", 0.3));
    healthTracker.refresh(caseId, "test-tenant", healthPolicy);

    var rollbackPolicy = new RollbackPolicy(null, 0.1, null, null, null, null);
    detector.checkActiveMonitors(caseId, healthTracker, rollbackPolicy);

    assertThat(categoryTracker.isSuppressed(caseId, "lint-fix")).isTrue();
  }

  @Test
  void resetClearsMonitors() {
    registry.register(area("stability", 0.8));
    var policy = new HealthPolicy(null, null, null, null, null, null);
    healthTracker.refresh(caseId, "test-tenant", policy);

    detector.onOutcome(caseId, outcome(ImprovementOutcome.OutcomeStatus.MERGED));
    detector.reset();

    assertThat(detector.activeMonitorCount(caseId)).isEqualTo(0);
  }

  @Test
  void firesEventOnRegressionDetected() {
    registry.register(area("stability", 0.8));
    var healthPolicy = new HealthPolicy(null, null, null, null, null, null);
    healthTracker.refresh(caseId, "test-tenant", healthPolicy);

    var outcome = outcome(ImprovementOutcome.OutcomeStatus.MERGED);
    detector.onOutcome(caseId, outcome);

    registry.deprecate("stability");
    registry.register(area("stability", 0.3));
    healthTracker.refresh(caseId, "test-tenant", healthPolicy);

    var rollbackPolicy = new RollbackPolicy(null, 0.1, null, null, null, null);
    detector.checkActiveMonitors(caseId, healthTracker, rollbackPolicy);

    assertThat(regressionDetectedEvents.fired()).hasSize(1);
    var event = regressionDetectedEvents.fired().get(0);
    assertThat(event.caseId()).isEqualTo(caseId);
    assertThat(event.category()).isEqualTo("lint-fix");
    assertThat(event.confidence()).isGreaterThan(0.0);
  }

  @Test
  void noEventWhenHealthStable() {
    registry.register(area("stability", 0.8));
    var healthPolicy = new HealthPolicy(null, null, null, null, null, null);
    healthTracker.refresh(caseId, "test-tenant", healthPolicy);

    var outcome = outcome(ImprovementOutcome.OutcomeStatus.MERGED);
    detector.onOutcome(caseId, outcome);

    var rollbackPolicy = new RollbackPolicy(null, null, null, null, null, null);
    detector.checkActiveMonitors(caseId, healthTracker, rollbackPolicy);

    assertThat(regressionDetectedEvents.fired()).isEmpty();
  }

  private ImprovementOutcome outcome(ImprovementOutcome.OutcomeStatus status) {
    return new ImprovementOutcome(
        caseId,
        UUID.randomUUID(),
        "lint-fix",
        "checkstyle",
        status,
        null,
        null,
        null,
        null,
        Instant.now(),
        Map.of());
  }

  private CapabilityArea area(String id, double health) {
    return new CapabilityArea() {
      public String id() {
        return id;
      }

      public String name() {
        return id;
      }

      public String description() {
        return "test";
      }

      public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
        return new CapabilityAreaAssessment(
            id,
            health,
            CapabilityAreaAssessment.LandscapePosition.AT_PARITY,
            0.5,
            0.3,
            1.67,
            Instant.now());
      }
    };
  }
}
