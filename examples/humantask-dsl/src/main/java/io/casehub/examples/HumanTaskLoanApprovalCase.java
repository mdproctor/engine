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
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.HumanRoutingConfig;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.time.Duration;
import java.util.Set;

/**
 * HumanTask — Loan Approval (DSL pathway).
 *
 * <p>Same case as {@code examples/yaml/humantask-loan-approval.yaml} and {@code
 * examples/humantask-annotated/}. AI workers assess credit and risk, then a human loan officer
 * makes the final decision via a WorkItem.
 *
 * <p>See also: examples/yaml/humantask-loan-approval.yaml (YAML pathway)
 * examples/humantask-annotated/ (annotation pathway)
 */
public final class HumanTaskLoanApprovalCase {

  private HumanTaskLoanApprovalCase() {}

  public static CaseDefinition define() {
    Capability assessCredit =
        Capability.of(
            "assessCredit",
            "{ applicantId: .application.applicantId, income: .application.income }",
            "{ creditScore: { score: .score, grade: .grade, factors: .factors } }");

    Capability evaluateRisk =
        Capability.of(
            "evaluateRisk",
            "{ creditScore: .creditScore, loanAmount: .application.loanAmount,"
                + " term: .application.term }",
            "{ riskAssessment: { level: .level, debtToIncome: .debtToIncome,"
                + " recommendation: .recommendation } }");

    return CaseDefinition.builder()
        .namespace("finance")
        .name("loan-approval")
        .version("1.0.0")
        .title("Loan Approval")
        .summary(
            "Processes loan applications — assesses credit, evaluates risk,"
                + " routes to human officer for final decision")
        .type(Path.parse("finance/lending"))
        .label(Path.parse("example/humantask"))
        .capabilities(assessCredit, evaluateRisk)
        .workers(
            Worker.builder()
                .name("credit-scorer")
                .capabilityName("assessCredit")
                .noFunction()
                .build(),
            Worker.builder()
                .name("risk-evaluator")
                .capabilityName("evaluateRisk")
                .noFunction()
                .build())
        .bindings(
            Binding.builder()
                .name("score-on-application")
                .capability(assessCredit)
                .on(new ContextChangeTrigger(".application != null and .creditScore == null"))
                .build(),
            Binding.builder()
                .name("evaluate-after-scoring")
                .capability(evaluateRisk)
                .on(new ContextChangeTrigger(".creditScore != null and .riskAssessment == null"))
                .build(),
            Binding.builder()
                .name("officer-approval")
                .judgment(
                    JudgmentTarget.builder()
                        .prompt("Review loan application")
                        .title("Review loan application")
                        .outcomes(Set.of("APPROVED", "DECLINED", "REFERRED"))
                        .expiresIn(Duration.ofHours(48))
                        .outputMapping(
                            "{ decision: { outcome: .outcome, officer: .officer,"
                                + " notes: .notes } }")
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
                .build())
        .milestones(
            Milestone.builder()
                .name("creditAssessed")
                .completionCriteria(".creditScore != null")
                .build(),
            Milestone.builder()
                .name("riskEvaluated")
                .completionCriteria(".riskAssessment != null")
                .build())
        .goals(
            Goal.builder()
                .name("loanDecided")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".decision != null")
                .build())
        .completion(GoalExpression.goal("loanDecided"))
        .build();
  }
}
