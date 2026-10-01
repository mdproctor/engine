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

import io.casehub.api.model.improvement.ComplianceLevel;
import io.casehub.api.model.improvement.HealthPolicy;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.RollbackPolicy;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.spi.improvement.CapabilityArea;
import io.casehub.engine.common.spi.event.ComplianceLevelChangedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadinessValidatorTest {

  private CapabilityAreaRegistry registry;
  private ReadinessValidator validator;
  private UUID caseId;
  private static final String TENANCY = "test-tenant";
  private TestEvent<ComplianceLevelChangedEvent> complianceLevelChangedEvents;

  @BeforeEach
  void setUp() {
    registry = new CapabilityAreaRegistry();
    var checklistProvider = new DefaultComplianceChecklistProvider();
    complianceLevelChangedEvents = new TestEvent<>();
    validator = new ReadinessValidator(registry, checklistProvider, complianceLevelChangedEvents);
    caseId = UUID.randomUUID();
  }

  @Test
  void noAreasRegistered_targetL1_fails() {
    var config = new ImprovementConfig(null, null, null, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L1_OBSERVE, config);
    assertThat(report.passed()).isFalse();
    assertThat(report.projectLevel()).isEqualTo(ComplianceLevel.L0_INERT);
    assertThat(report.areas()).isEmpty();
  }

  @Test
  void areaWithData_targetL1_passes() {
    registry.register(areaWithData("stability", 0.8));
    var config = new ImprovementConfig(null, null, null, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L1_OBSERVE, config);
    assertThat(report.passed()).isTrue();
    assertThat(report.projectLevel()).isEqualTo(ComplianceLevel.L1_OBSERVE);
    assertThat(report.areas()).hasSize(1);
    assertThat(report.areas().get(0).areaLevel()).isEqualTo(ComplianceLevel.L1_OBSERVE);
  }

  @Test
  void targetL2_withoutEvolutionEnabled_fails() {
    registry.register(areaWithData("stability", 0.8));
    var config = new ImprovementConfig(null, null, null, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L2_PROPOSE, config);
    assertThat(report.passed()).isFalse();
    assertThat(report.projectLevel()).isEqualTo(ComplianceLevel.L1_OBSERVE);
  }

  @Test
  void targetL2_withEvolutionEnabled_passes() {
    registry.register(areaWithData("stability", 0.8));
    var config =
        new ImprovementConfig(null, 2, null, null, null, true, null, null, null, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L2_PROPOSE, config);
    assertThat(report.passed()).isTrue();
    assertThat(report.projectLevel()).isEqualTo(ComplianceLevel.L2_PROPOSE);
  }

  @Test
  void targetL3_withoutRollbackPolicy_fails() {
    registry.register(areaWithData("stability", 0.8));
    var config =
        new ImprovementConfig(null, 2, null, null, null, true, null, null, null, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L3_AUTONOMOUS, config);
    assertThat(report.passed()).isFalse();
    assertThat(report.projectLevel()).isEqualTo(ComplianceLevel.L2_PROPOSE);
  }

  @Test
  void targetL3_fullyConfigured_passes() {
    registry.register(areaWithData("stability", 0.8));
    var rollbackPolicy = new RollbackPolicy(null, null, null, null, null, null);
    var healthPolicy = new HealthPolicy(0.6, null, null, null, null, null);
    var config =
        new ImprovementConfig(
            null, 2, null, null, null, true, null, rollbackPolicy, healthPolicy, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L3_AUTONOMOUS, config);
    assertThat(report.passed()).isTrue();
    assertThat(report.projectLevel()).isEqualTo(ComplianceLevel.L3_AUTONOMOUS);
  }

  @Test
  void perAreaBreakdown_showsDifferentLevels() {
    registry.register(areaWithData("stability", 0.8));
    registry.register(neutralArea("cognitive-memory"));
    var config =
        new ImprovementConfig(null, 2, null, null, null, true, null, null, null, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L2_PROPOSE, config);

    var stabilityArea =
        report.areas().stream()
            .filter(a -> a.areaId().equals("stability"))
            .findFirst()
            .orElseThrow();
    var memoryArea =
        report.areas().stream()
            .filter(a -> a.areaId().equals("cognitive-memory"))
            .findFirst()
            .orElseThrow();

    assertThat(stabilityArea.areaLevel()).isEqualTo(ComplianceLevel.L2_PROPOSE);
    assertThat(memoryArea.areaLevel()).isEqualTo(ComplianceLevel.L0_INERT);
  }

  @Test
  void projectLevel_isMinOfNonL0Areas() {
    registry.register(areaWithData("stability", 0.8));
    registry.register(areaWithData("performance", 0.8));
    registry.register(neutralArea("cognitive-memory"));
    var config = new ImprovementConfig(null, null, null, null, null);
    var report = validator.validate(caseId, TENANCY, ComplianceLevel.L1_OBSERVE, config);
    assertThat(report.projectLevel()).isEqualTo(ComplianceLevel.L1_OBSERVE);
  }

  @Test
  void firesEventWhenComplianceLevelChanges() {
    registry.register(areaWithData("stability", 0.8));
    var config = new ImprovementConfig(null, null, null, null, null);
    validator.validate(caseId, TENANCY, ComplianceLevel.L1_OBSERVE, config);
    assertThat(complianceLevelChangedEvents.fired()).isEmpty();

    var configL2 =
        new ImprovementConfig(null, 2, null, null, null, true, null, null, null, null, null);
    validator.validate(caseId, TENANCY, ComplianceLevel.L2_PROPOSE, configL2);

    assertThat(complianceLevelChangedEvents.fired()).hasSize(1);
    var event = complianceLevelChangedEvents.fired().get(0);
    assertThat(event.caseId()).isEqualTo(caseId);
    assertThat(event.oldLevel()).isEqualTo(ComplianceLevel.L1_OBSERVE);
    assertThat(event.newLevel()).isEqualTo(ComplianceLevel.L2_PROPOSE);
  }

  @Test
  void noEventOnFirstValidation() {
    registry.register(areaWithData("stability", 0.8));
    var config = new ImprovementConfig(null, null, null, null, null);
    validator.validate(caseId, TENANCY, ComplianceLevel.L1_OBSERVE, config);
    assertThat(complianceLevelChangedEvents.fired()).isEmpty();
  }

  @Test
  void noEventWhenLevelUnchanged() {
    registry.register(areaWithData("stability", 0.8));
    var config = new ImprovementConfig(null, null, null, null, null);
    validator.validate(caseId, TENANCY, ComplianceLevel.L1_OBSERVE, config);
    validator.validate(caseId, TENANCY, ComplianceLevel.L1_OBSERVE, config);
    assertThat(complianceLevelChangedEvents.fired()).isEmpty();
  }

  private CapabilityArea areaWithData(String id, double health) {
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

  private CapabilityArea neutralArea(String id) {
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
            0.5,
            CapabilityAreaAssessment.LandscapePosition.ABSENT,
            0.0,
            0.0,
            0.0,
            Instant.now());
      }
    };
  }
}
