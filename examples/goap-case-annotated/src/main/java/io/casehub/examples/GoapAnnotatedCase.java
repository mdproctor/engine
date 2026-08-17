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

import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.PlanningMode;
import io.casehub.engine.annotations.Worker;
import java.util.List;

@Case(
    namespace = "example",
    name = "GOAP Document Review",
    version = "1.0.0",
    planning = PlanningMode.GOAP)
public interface GoapAnnotatedCase {

  @Worker(capability = "analyse", cost = 0.2)
  default AnalysisResult analyse(String document) {
    return new AnalysisResult("Summary of: " + document);
  }

  @Worker(capability = "extractClauses", cost = 0.3)
  default ClauseList extract(String document, AnalysisResult analysis) {
    return new ClauseList(List.of("clause1", "clause2"));
  }

  @Worker(capability = "assessRisk", cost = 0.5)
  default RiskAssessment assess(AnalysisResult analysis, ClauseList clauses) {
    return new RiskAssessment("LOW");
  }

  @Goal(value = "Risk assessment completed", condition = ".riskAssessment != null")
  default void done() {}

  record AnalysisResult(String summary) {}

  record ClauseList(List<String> clauses) {}

  record RiskAssessment(String level) {}
}
