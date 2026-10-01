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

import io.casehub.api.model.improvement.ImprovementBudget;
import io.casehub.api.model.improvement.ImprovementRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementBudgetEnforcerTest {

  private static final String TENANT = "test-tenant";
  private ImprovementBudgetEnforcer enforcer;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    enforcer = new ImprovementBudgetEnforcer(new InMemoryDenyPatternStore());
    caseId = UUID.randomUUID();
  }

  @Test
  void allowedWhenAllChecksPass() {
    var budget = new ImprovementBudget(null, null, null, null, null, null, null);
    var request =
        new ImprovementRequest(
            "operational", "dependency-update", "hibernate-core", 20, Map.of(), null);

    var result = enforcer.check(caseId, budget, request, TENANT);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Allowed.class);
  }

  @Test
  void concurrentLimitDeniesWhenExceeded() {
    var budget = new ImprovementBudget(1, null, null, null, null, null, null);
    var request =
        new ImprovementRequest("operational", "lint-fix", "checkstyle", 10, Map.of(), null);

    enforcer.recordStart(caseId, request);

    var result = enforcer.check(UUID.randomUUID(), budget, request, TENANT);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("Concurrent improvement limit");
  }

  @Test
  void dailyLimitDeniesWhenExceeded() {
    var budget = new ImprovementBudget(null, 1, null, null, null, null, null);
    var request =
        new ImprovementRequest("operational", "lint-fix", "checkstyle", 10, Map.of(), null);

    enforcer.recordStart(caseId, request);
    enforcer.recordCompletion(caseId);

    var result = enforcer.check(UUID.randomUUID(), budget, request, TENANT);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("Daily improvement limit");
  }

  @Test
  void prSizeLimitDeniesOversizedChange() {
    var budget = new ImprovementBudget(null, null, null, null, null, null, 50);
    var request = new ImprovementRequest("operational", "recipe", "cleanup", 100, Map.of(), null);

    var result = enforcer.check(caseId, budget, request, TENANT);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("exceeds limit");
  }

  @Test
  void emptyRepoAllowListAllowsAll() {
    var budget = new ImprovementBudget(null, null, null, List.of(), null, null, null);
    var request =
        new ImprovementRequest("operational", "lint-fix", "checkstyle", 10, Map.of(), null);

    var result = enforcer.check(caseId, budget, request, TENANT);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Allowed.class);
  }

  @Test
  void activeCountTracksState() {
    var request = new ImprovementRequest("operational", "lint-fix", "target", 10, Map.of(), null);
    assertThat(enforcer.activeCount()).isEqualTo(0);
    enforcer.recordStart(caseId, request);
    assertThat(enforcer.activeCount()).isEqualTo(1);
    enforcer.recordCompletion(caseId);
    assertThat(enforcer.activeCount()).isEqualTo(0);
  }
}
