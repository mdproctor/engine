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
package io.casehub.examples;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Customize;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Milestone;
import io.casehub.engine.annotations.Worker;
import java.util.Map;

/**
 * Sequential — Employee Onboarding (annotation pathway).
 *
 * <p>Annotations cannot express {@code planningStrategy} or {@code sequence} natively. The
 * {@code @Customize} escape hatch drops into the DSL to fill these gaps — showing exactly where
 * annotations reach their limit.
 *
 * <p>See also: examples/yaml/sequential-onboarding.yaml (YAML pathway) examples/sequential-dsl/
 * (DSL pathway)
 */
@Case(
    namespace = "hr",
    name = "EmployeeOnboarding",
    version = "1.0.0",
    title = "Employee Onboarding",
    summary =
        "Onboards a new employee — collects documents, runs background check,"
            + " provisions access, schedules orientation")
public interface SequentialOnboardingAnnotated {

  @Worker(capability = "collectDocuments", description = "Collects employment documents")
  @Bind(contextChange = ".employee != null and .documentsCollected == null")
  default Map<String, Object> collectDocuments(Map<String, Object> input) {
    return Map.of("documentsCollected", Map.of("complete", true, "missing", java.util.List.of()));
  }

  @Worker(capability = "backgroundCheck", description = "Runs background verification")
  @Bind(contextChange = ".documentsCollected != null and .backgroundResult == null")
  default Map<String, Object> backgroundCheck(Map<String, Object> input) {
    return Map.of("backgroundResult", Map.of("status", "CLEAR", "completedAt", "2026-01-15"));
  }

  @Worker(capability = "provisionAccess", description = "Provisions IT access")
  @Bind(contextChange = ".backgroundResult != null and .accessProvisioned == null")
  default Map<String, Object> provisionAccess(Map<String, Object> input) {
    return Map.of(
        "accessProvisioned",
        Map.of(
            "email", "new@company.com", "systems", java.util.List.of("jira", "slack", "github")));
  }

  @Worker(capability = "scheduleOrientation", description = "Schedules orientation")
  @Bind(contextChange = ".accessProvisioned != null and .orientation == null")
  default Map<String, Object> scheduleOrientation(Map<String, Object> input) {
    return Map.of(
        "orientation", Map.of("date", "2026-01-20", "location", "HQ-3F", "confirmed", true));
  }

  @Milestone(
      name = "documentsComplete",
      completionCriteria = ".documentsCollected != null and .documentsCollected.complete == true")
  default void documentsComplete() {}

  @Milestone(
      name = "backgroundCleared",
      completionCriteria = ".backgroundResult != null and .backgroundResult.status == \"CLEAR\"")
  default void backgroundCleared() {}

  @Goal(
      value = "Employee fully onboarded",
      condition = ".orientation != null and .orientation.confirmed == true")
  default void onboardingComplete() {}

  @Goal(
      value = "Background check failed",
      condition = ".backgroundResult != null and .backgroundResult.status == \"FAIL\"",
      kind = "FAILURE")
  default void backgroundFailed() {}

  /**
   * Annotations cannot express planningStrategy or sequence natively. This @Customize block drops
   * into the DSL to set sequential planning.
   */
  @Customize
  static void customize(CaseDefinition.Builder builder) {
    builder.planningStrategy("sequential");
  }
}
