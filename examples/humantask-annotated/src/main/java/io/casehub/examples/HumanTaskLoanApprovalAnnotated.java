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

import io.casehub.api.model.Binding;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.HumanRoutingConfig;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Customize;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Milestone;
import io.casehub.engine.annotations.Worker;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * HumanTask — Loan Approval (annotation pathway).
 *
 * <p>Annotations cannot express {@code humanTask:} binding targets natively — {@code @Bind} only
 * targets capabilities. The {@code @Customize} escape hatch drops into the DSL to add the judgment
 * binding — showing exactly where annotations reach their limit.
 *
 * <p>See also: examples/yaml/humantask-loan-approval.yaml (YAML pathway)
 * examples/humantask-dsl/ (DSL pathway)
 */
@Case(
    namespace = "finance",
    name = "LoanApproval",
    version = "1.0.0",
    title = "Loan Approval",
    summary =
        "Processes loan applications — assesses credit, evaluates risk,"
            + " routes to human officer for final decision")
public interface HumanTaskLoanApprovalAnnotated {

  @Worker(capability = "assessCredit", description = "Automated credit scoring engine")
  @Bind(contextChange = ".application != null and .creditScore == null")
  default Map<String, Object> assessCredit(Map<String, Object> input) {
    return Map.of("creditScore", Map.of("score", 720, "grade", "A", "factors", java.util.List.of("payment-history")));
  }

  @Worker(capability = "evaluateRisk", description = "Loan risk evaluation engine")
  @Bind(contextChange = ".creditScore != null and .riskAssessment == null")
  default Map<String, Object> evaluateRisk(Map<String, Object> input) {
    return Map.of("riskAssessment", Map.of("level", "LOW", "debtToIncome", 0.32, "recommendation", "APPROVE"));
  }

  @Milestone(name = "creditAssessed", completionCriteria = ".creditScore != null")
  default void creditAssessed() {}

  @Milestone(name = "riskEvaluated", completionCriteria = ".riskAssessment != null")
  default void riskEvaluated() {}

  @Goal(value = "Loan officer has decided", condition = ".decision != null")
  default void loanDecided() {}

  /**
   * Annotations cannot express humanTask binding targets — @Bind targets capabilities only. This
   * @Customize block drops into the DSL to add the judgment binding with candidate groups, outcomes,
   * and expiration.
   */
  @Customize
  static void customize(CaseDefinition.Builder builder) {
    builder.binding(
        Binding.builder()
            .name("officer-approval")
            .judgment(
                JudgmentTarget.builder()
                    .prompt("Review loan application")
                    .title("Review loan application")
                    .outcomes(Set.of("APPROVED", "DECLINED", "REFERRED"))
                    .expiresIn(Duration.ofHours(48))
                    .outputMapping(
                        "{ decision: { outcome: .outcome, officer: .officer, notes: .notes } }")
                    .human(
                        new HumanRoutingConfig(
                            null,
                            new CandidateSetSpec.Inline(
                                StaticSetStrategy.of("loan-officers", "senior-underwriters")),
                            null,
                            4,
                            null))
                    .build())
            .on(new ContextChangeTrigger(".riskAssessment != null and .decision == null"))
            .build());
  }
}
