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
import io.casehub.api.model.StandardGoalKind;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.List;
import java.util.Map;

/**
 * GOAP — Contract Review (DSL pathway).
 *
 * <p>Same case as {@code examples/yaml/goap-contract-review.yaml} and {@code
 * examples/goap-annotated/}. The GOAP planner computes optimal execution order from declared
 * preconditions and effects — no explicit sequencing required.
 *
 * <p>See also: examples/yaml/goap-contract-review.yaml (YAML pathway) examples/goap-annotated/
 * (annotation pathway)
 */
public final class GoapContractReviewCase {

  private GoapContractReviewCase() {}

  public static CaseDefinition define() {
    Capability analyse =
        Capability.of(
            "analyse",
            "{ contract: .contract }",
            "{ analysisResult: { summary: .summary, contractType: .contractType } }");

    Capability extractClauses =
        Capability.of(
            "extractClauses",
            "{ contract: .contract, analysisResult: .analysisResult }",
            "{ clauseList: { clauses: .clauses } }");

    Capability externalLegalOpinion =
        Capability.builder()
            .name("externalLegalOpinion")
            .inputSchema(".")
            .outputSchema(".")
            .description("External legal expert opinion — satisfied by A2A, MCP, or provisioner")
            .build();

    Capability assessRisk =
        Capability.of(
            "assessRisk",
            "{ analysisResult: .analysisResult, clauseList: .clauseList,"
                + " legalOpinion: .legalOpinion, priorReview: .priorReview }",
            "{ riskAssessment: { level: .level, jurisdiction: .jurisdiction,"
                + " priorContext: .priorContext } }");

    return CaseDefinition.builder()
        .namespace("legal")
        .name("contract-review")
        .version("1.0.0")
        .title("Contract Review")
        .summary(
            "Reviews incoming contracts — analyses structure, extracts clauses,"
                + " assesses risk with optional external legal opinion")
        .type(Path.parse("legal/review"))
        .label(Path.parse("example/goap"))
        .decompositionStrategy("goap")
        .capabilities(analyse, extractClauses, externalLegalOpinion, assessRisk)
        .workers(
            Worker.builder()
                .name("contract-analyser")
                .capabilityName("analyse")
                .noFunction()
                .build(),
            Worker.builder()
                .name("clause-extractor")
                .capabilityName("extractClauses")
                .noFunction()
                .build(),
            Worker.builder()
                .name("risk-assessor")
                .capabilityName("assessRisk")
                .noFunction()
                .build())
        .bindings(
            Binding.builder()
                .name("start-review")
                .capability(analyse)
                .on(new ContextChangeTrigger(".contract != null and .analysisResult == null"))
                .build(),
            Binding.builder()
                .name("extract-after-analysis")
                .capability(extractClauses)
                .on(new ContextChangeTrigger(".analysisResult != null and .clauseList == null"))
                .build(),
            Binding.builder()
                .name("assess-after-extraction")
                .capability(assessRisk)
                .on(new ContextChangeTrigger(".clauseList != null and .riskAssessment == null"))
                .build())
        .goapActions(
            List.of(
                new GoapAction(
                    "analyse", Map.of(), Map.of("analysisResult", true), 0.2, 0.1, Map.of()),
                new GoapAction(
                    "extractClauses",
                    Map.of("analysisResult", true),
                    Map.of("clauseList", true),
                    0.3),
                new GoapAction(
                    "assessRisk",
                    Map.of("analysisResult", true, "clauseList", true),
                    Map.of("riskAssessment", true),
                    0.5,
                    0.0,
                    Map.of("legalOpinion", true, "priorReview", true))))
        .goals(
            Goal.builder()
                .name("reviewComplete")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".riskAssessment != null")
                .build())
        .completion(GoalExpression.goal("reviewComplete"))
        .build();
  }
}
