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

import io.casehub.api.model.stigmergy.ImprovementBudget;
import io.casehub.api.model.stigmergy.ImprovementRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementBudgetEnforcerTest {

  private ImprovementBudgetEnforcer enforcer;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    enforcer = new ImprovementBudgetEnforcer();
    caseId = UUID.randomUUID();
  }

  @Test
  void structuralDenyBlocksImprovementBudgetPath() {
    var budget = new ImprovementBudget(null, null, null, null, List.of(), null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/main/java/io/casehub/api/model/stigmergy/ImprovementBudget.java"),
            10,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("Structural self-modification denied");
  }

  @Test
  void structuralDenyBlocksEnforcerPath() {
    var budget = new ImprovementBudget(null, null, null, null, List.of(), null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "recipe",
            "cleanup",
            "casehubio/engine",
            List.of(
                "src/main/java/io/casehub/engine/internal/improvement/ImprovementBudgetEnforcer.java"),
            5,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
  }

  @Test
  void structuralDenyCannotBeOverriddenByEmptyUserDenyList() {
    var budget = new ImprovementBudget(null, null, null, null, List.of(), null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "recipe",
            "cleanup",
            "casehubio/engine",
            List.of("src/main/java/io/casehub/api/model/stigmergy/ImprovementConfig.java"),
            5,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
  }

  @Test
  void allowedWhenAllChecksPass() {
    var budget = new ImprovementBudget(null, null, null, null, null, null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate-core",
            "casehubio/engine",
            List.of("pom.xml"),
            20,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Allowed.class);
  }

  @Test
  void concurrentLimitDeniesWhenExceeded() {
    var budget = new ImprovementBudget(1, null, null, null, null, null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/Foo.java"),
            10,
            Map.of());

    enforcer.recordStart(caseId, request);

    var result = enforcer.check(UUID.randomUUID(), budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("Concurrent improvement limit");
  }

  @Test
  void dailyLimitDeniesWhenExceeded() {
    var budget = new ImprovementBudget(null, 1, null, null, null, null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/Foo.java"),
            10,
            Map.of());

    enforcer.recordStart(caseId, request);
    enforcer.recordCompletion(caseId);

    var result = enforcer.check(UUID.randomUUID(), budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("Daily improvement limit");
  }

  @Test
  void repoAllowListDeniesUnlistedRepo() {
    var budget =
        new ImprovementBudget(null, null, null, List.of("casehubio/blocks"), null, null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/Foo.java"),
            10,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("Repository not in allowed list");
  }

  @Test
  void prSizeLimitDeniesOversizedChange() {
    var budget = new ImprovementBudget(null, null, null, null, null, null, 50);
    var request =
        new ImprovementRequest(
            "operational",
            "recipe",
            "cleanup",
            "casehubio/engine",
            List.of("src/Foo.java"),
            100,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("exceeds limit");
  }

  @Test
  void userDenyListBlocksMatchingPath() {
    var budget = new ImprovementBudget(null, null, null, null, List.of("**/test/**"), null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/test/java/Foo.java"),
            10,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Denied.class);
    assertThat(((ImprovementBudgetEnforcer.BudgetCheck.Denied) result).reason())
        .contains("Path denied by configuration");
  }

  @Test
  void emptyRepoAllowListAllowsAll() {
    var budget = new ImprovementBudget(null, null, null, List.of(), null, null, null);
    var request =
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/Foo.java"),
            10,
            Map.of());

    var result = enforcer.check(caseId, budget, request);

    assertThat(result).isInstanceOf(ImprovementBudgetEnforcer.BudgetCheck.Allowed.class);
  }

  @Test
  void activeCountTracksState() {
    var request =
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "target",
            "casehubio/engine",
            List.of("src/Foo.java"),
            10,
            Map.of());
    assertThat(enforcer.activeCount()).isEqualTo(0);
    enforcer.recordStart(caseId, request);
    assertThat(enforcer.activeCount()).isEqualTo(1);
    enforcer.recordCompletion(caseId);
    assertThat(enforcer.activeCount()).isEqualTo(0);
  }
}
