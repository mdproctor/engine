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

import io.casehub.api.model.GoalExpression;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Completion;
import io.casehub.engine.annotations.Effect;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Param;
import io.casehub.engine.annotations.PlanningMode;
import io.casehub.engine.annotations.SoftDependency;
import io.casehub.engine.annotations.Worker;
import java.util.List;

@Case(
    namespace = "legal",
    name = "ContractReview",
    version = "1.0.0",
    title = "Contract Review",
    planning = PlanningMode.GOAP)
public interface GoapAnnotatedCase {

  @Worker(
      capability = "analyse",
      cost = 0.2,
      benefit = 0.1,
      description = "Analyses contract structure and key terms")
  default AnalysisResult analyse(String contract) {
    return new AnalysisResult("Summary of: " + contract, "standard");
  }

  @Worker(capability = "extractClauses", cost = 0.3)
  default ClauseList extractClauses(String contract, AnalysisResult analysisResult) {
    return new ClauseList(List.of("Limitation of liability", "Indemnification", "Termination"));
  }

  @Worker(capability = "assessRisk", cost = 0.5)
  @Effect("riskAssessment")
  default RiskReport assessRisk(
      AnalysisResult analysisResult,
      ClauseList clauseList,
      @SoftDependency PriorReview priorReview,
      @Param("jurisdiction") String jurisdiction) {
    String prior = priorReview != null ? priorReview.notes() : "none";
    return new RiskReport("LOW", jurisdiction, prior);
  }

  @Goal(value = "Risk assessment completed", condition = ".riskAssessment != null")
  @Completion
  default GoalExpression reviewComplete() {
    return GoalExpression.goal("reviewComplete");
  }

  record AnalysisResult(String summary, String contractType) {}

  record ClauseList(List<String> clauses) {}

  record RiskReport(String level, String jurisdiction, String priorContext) {}

  record PriorReview(String notes) {}
}
