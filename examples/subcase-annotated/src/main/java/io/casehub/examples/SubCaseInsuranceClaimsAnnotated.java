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
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.SubCase;
import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Customize;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Milestone;
import io.casehub.engine.annotations.Worker;
import java.util.Map;

/**
 * SubCase — Insurance Claims Processing (annotation pathway).
 *
 * <p>Annotations cannot express {@code subCase:} binding targets natively. The {@code @Customize}
 * escape hatch drops into the DSL to add sub-case bindings — showing exactly where annotations
 * reach their limit.
 *
 * <p>See also: examples/yaml/subcase-insurance-claims.yaml (YAML pathway)
 * examples/subcase-dsl/ (DSL pathway)
 */
@Case(
    namespace = "insurance",
    name = "ClaimsProcessing",
    version = "1.0.0",
    title = "Claims Processing",
    summary =
        "Processes insurance claims — triages, delegates investigation"
            + " to sub-cases, adjudicates outcome")
public interface SubCaseInsuranceClaimsAnnotated {

  @Worker(capability = "triageClaim", description = "Automated claim triage and validation")
  @Bind(contextChange = ".claim != null and .triage == null")
  default Map<String, Object> triageClaim(Map<String, Object> input) {
    return Map.of("triage", Map.of("category", "auto", "priority", "MEDIUM", "valid", true));
  }

  @Worker(capability = "adjudicate", description = "Automated claim adjudication")
  @Bind(contextChange = ".investigation != null and .adjudication == null")
  default Map<String, Object> adjudicate(Map<String, Object> input) {
    return Map.of(
        "adjudication", Map.of("decision", "APPROVED", "amount", 15000, "reasoning", "Valid claim"));
  }

  @Milestone(
      name = "claimTriaged",
      completionCriteria = ".triage != null and .triage.valid == true")
  default void claimTriaged() {}

  @Milestone(name = "investigationComplete", completionCriteria = ".investigation != null")
  default void investigationComplete() {}

  @Goal(value = "Claim fully adjudicated", condition = ".adjudication != null")
  default void claimResolved() {}

  @Goal(
      value = "Claim failed triage",
      condition = ".triage != null and .triage.valid == false",
      kind = "FAILURE")
  default void claimInvalid() {}

  /**
   * Annotations cannot express subCase binding targets. This @Customize block drops into the DSL to
   * add sub-case bindings with input/output mapping and grouped M-of-N sub-cases.
   */
  @Customize
  static void customize(CaseDefinition.Builder builder) {
    builder
        .binding(
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
                                + " evidence: .evidence, estimatedLoss: .estimatedLoss } }")
                        .waitForCompletion(true)
                        .build())
                .on(
                    new ContextChangeTrigger(
                        ".triage != null and .triage.valid == true"
                            + " and .investigation == null"))
                .build())
        .binding(
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
                .build());
  }
}
