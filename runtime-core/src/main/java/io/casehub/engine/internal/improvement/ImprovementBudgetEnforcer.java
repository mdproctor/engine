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

import io.casehub.api.model.stigmergy.ImprovementBudget;
import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ImprovementBudgetEnforcer implements Resettable {

  private static final Set<String> STRUCTURAL_DENIED_PATTERNS =
      Set.of(
          "ImprovementBudget",
          "ImprovementBudgetEnforcer",
          "ImprovementConfig",
          "SafetyConfig",
          "improvement-case-template");

  private final ConcurrentHashMap<UUID, Instant> activeImprovements = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<LocalDate, AtomicInteger> dailyCounts = new ConcurrentHashMap<>();
  private volatile Instant lastCompletionTime = Instant.EPOCH;

  public sealed interface BudgetCheck permits BudgetCheck.Allowed, BudgetCheck.Denied {
    record Allowed() implements BudgetCheck {}

    record Denied(String reason) implements BudgetCheck {}
  }

  public BudgetCheck check(UUID caseId, ImprovementBudget budget, ImprovementRequest request) {
    for (String path : request.targetPaths()) {
      for (String pattern : STRUCTURAL_DENIED_PATTERNS) {
        if (path.contains(pattern)) {
          return new BudgetCheck.Denied("Structural self-modification denied: " + path);
        }
      }
    }

    for (String path : request.targetPaths()) {
      for (String deniedPattern : budget.effectiveDeniedPaths()) {
        if (matchesGlob(path, deniedPattern)) {
          return new BudgetCheck.Denied("Path denied by configuration: " + path);
        }
      }
    }

    if (!budget.effectiveAllowedRepos().isEmpty()
        && !budget.effectiveAllowedRepos().contains(request.targetRepo())) {
      return new BudgetCheck.Denied("Repository not in allowed list: " + request.targetRepo());
    }

    int active = activeImprovements.size();
    if (active >= budget.effectiveMaxConcurrent()) {
      return new BudgetCheck.Denied(
          "Concurrent improvement limit reached: "
              + active
              + "/"
              + budget.effectiveMaxConcurrent());
    }

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    int todayCount = dailyCounts.getOrDefault(today, new AtomicInteger(0)).get();
    if (todayCount >= budget.effectiveMaxPerDay()) {
      return new BudgetCheck.Denied(
          "Daily improvement limit reached: " + todayCount + "/" + budget.effectiveMaxPerDay());
    }

    if (!lastCompletionTime.equals(Instant.EPOCH)) {
      long minutesSinceLast = Duration.between(lastCompletionTime, Instant.now()).toMinutes();
      if (minutesSinceLast < budget.effectiveCooldownMinutes()) {
        return new BudgetCheck.Denied(
            "Cooldown active: "
                + (budget.effectiveCooldownMinutes() - minutesSinceLast)
                + " minutes remaining");
      }
    }

    if (request.estimatedSize() > budget.effectiveMaxPRSize()) {
      return new BudgetCheck.Denied(
          "Estimated change size "
              + request.estimatedSize()
              + " exceeds limit "
              + budget.effectiveMaxPRSize());
    }

    return new BudgetCheck.Allowed();
  }

  public void recordStart(UUID improvementCaseId) {
    activeImprovements.put(improvementCaseId, Instant.now());
    dailyCounts
        .computeIfAbsent(LocalDate.now(ZoneOffset.UTC), k -> new AtomicInteger(0))
        .incrementAndGet();
  }

  public void recordCompletion(UUID improvementCaseId) {
    activeImprovements.remove(improvementCaseId);
    lastCompletionTime = Instant.now();
  }

  public int activeCount() {
    return activeImprovements.size();
  }

  @Override
  public void reset() {
    activeImprovements.clear();
    dailyCounts.clear();
    lastCompletionTime = Instant.EPOCH;
  }

  private static boolean matchesGlob(String path, String glob) {
    String regex =
        glob.replace(".", "\\.")
            .replace("**", "@@DOUBLESTAR@@")
            .replace("*", "[^/]*")
            .replace("@@DOUBLESTAR@@", ".*");
    return path.matches(regex);
  }
}
