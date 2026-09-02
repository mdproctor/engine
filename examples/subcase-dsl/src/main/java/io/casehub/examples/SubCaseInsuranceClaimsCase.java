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
import io.casehub.api.model.Milestone;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.SubCase;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;

/**
 * SubCase — Insurance Claims Processing (DSL pathway).
 *
 * <p>Same case as {@code examples/yaml/subcase-insurance-claims.yaml} and {@code
 * examples/subcase-annotated/}. The main case triages claims and delegates investigation to a
 * specialised sub-case definition.
 *
 * <p>See also: examples/yaml/subcase-insurance-claims.yaml (YAML pathway)
 * examples/subcase-annotated/ (annotation pathway)
 */
public final class SubCaseInsuranceClaimsCase {

  private SubCaseInsuranceClaimsCase() {}

  public static CaseDefinition define() {
    Capability triageClaim =
        Capability.of(
            "triageClaim",
            "{ claimId: .claim.id, type: .claim.type, documents: .claim.documents }",
            "{ triage: { category: .category, priority: .priority, valid: .valid } }");

    Capability adjudicate =
        Capability.of(
            "adjudicate",
            "{ claim: .claim, triage: .triage, investigation: .investigation }",
            "{ adjudication: { decision: .decision, amount: .amount,"
                + " reasoning: .reasoning } }");

    return CaseDefinition.builder()
        .namespace("insurance")
        .name("claims-processing")
        .version("1.0.0")
        .title("Claims Processing")
        .summary(
            "Processes insurance claims — triages, delegates investigation"
                + " to sub-cases, adjudicates outcome")
        .type(Path.parse("insurance/claims"))
        .label(Path.parse("example/subcase"))
        .capabilities(triageClaim, adjudicate)
        .workers(
            Worker.builder()
                .name("claim-triager")
                .capabilityName("triageClaim")
                .noFunction()
                .build(),
            Worker.builder().name("adjudicator").capabilityName("adjudicate").noFunction().build())
        .bindings(
            Binding.builder()
                .name("triage-on-submission")
                .capability(triageClaim)
                .on(new ContextChangeTrigger(".claim != null and .triage == null"))
                .build(),
            Binding.builder()
                .name("investigate-claim")
                .subCase(
                    SubCase.builder()
                        .namespace("insurance")
                        .name("claim-investigation")
                        .version("1.0.0")
                        .inputMapping(
                            "{ claimId: .claim.id, category: .triage.category,"
                                + " documents: .claim.documents }")
                        .outputMapping(
                            "{ investigation: { findings: .findings,"
                                + " evidence: .evidence,"
                                + " estimatedLoss: .estimatedLoss } }")
                        .waitForCompletion(true)
                        .build())
                .on(
                    new ContextChangeTrigger(
                        ".triage != null and .triage.valid == true"
                            + " and .investigation == null"))
                .build(),
            Binding.builder()
                .name("independent-assessments")
                .subCase(
                    SubCase.builder()
                        .namespace("insurance")
                        .name("claim-investigation")
                        .version("1.0.0")
                        .inputMapping("{ claimId: .claim.id, category: .triage.category }")
                        .outputMapping("{ peerReview: . }")
                        .groupId("peer-review")
                        .totalInGroup(3)
                        .requiredCount(2)
                        .onThresholdReached(OnThresholdReached.CANCEL)
                        .build())
                .on(
                    new ContextChangeTrigger(
                        ".triage != null and .triage.priority == \"HIGH\""
                            + " and .peerReview == null"))
                .when(".claim.amount > 100000")
                .build(),
            Binding.builder()
                .name("adjudicate-after-investigation")
                .capability(adjudicate)
                .on(new ContextChangeTrigger(".investigation != null and .adjudication == null"))
                .build())
        .milestones(
            Milestone.builder()
                .name("claimTriaged")
                .completionCriteria(".triage != null and .triage.valid == true")
                .build(),
            Milestone.builder()
                .name("investigationComplete")
                .completionCriteria(".investigation != null")
                .build())
        .goals(
            Goal.builder()
                .name("claimResolved")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".adjudication != null")
                .build(),
            Goal.builder()
                .name("claimInvalid")
                .kind(StandardGoalKind.FAILURE)
                .condition(".triage != null and .triage.valid == false")
                .build())
        .completion(GoalExpression.goal("claimResolved"), GoalExpression.goal("claimInvalid"))
        .build();
  }
}
