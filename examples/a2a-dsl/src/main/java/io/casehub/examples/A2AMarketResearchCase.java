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
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;

/**
 * A2A — Market Research (DSL pathway).
 *
 * <p>Same case as {@code examples/yaml/a2a-market-research.yaml} and {@code
 * examples/a2a-annotated/}. Remote A2A-compliant agents gather intelligence, analyse trends, and
 * produce assessments. In the DSL pathway, workers use {@code noFunction()} — the {@code a2a:}
 * block is YAML-specific configuration wired at deployment time.
 *
 * <p>See also: examples/yaml/a2a-market-research.yaml (YAML pathway) examples/a2a-annotated/
 * (annotation pathway)
 */
public final class A2AMarketResearchCase {

  private A2AMarketResearchCase() {}

  public static CaseDefinition define() {
    Capability gatherIntelligence =
        Capability.of(
            "gatherIntelligence",
            "{ sector: .brief.sector, competitors: .brief.competitors }",
            "{ intelligence: { sources: .sources, insights: .insights,"
                + " confidence: .confidence } }");

    Capability analyseTrends =
        Capability.of(
            "analyseTrends",
            "{ intelligence: .intelligence, timeframe: .brief.timeframe }",
            "{ trends: { patterns: .patterns, forecast: .forecast, risks: .risks } }");

    Capability produceAssessment =
        Capability.of(
            "produceAssessment",
            "{ intelligence: .intelligence, trends: .trends, brief: .brief }",
            "{ assessment: { summary: .summary, recommendations: .recommendations,"
                + " confidence: .confidence } }");

    return CaseDefinition.builder()
        .namespace("intelligence")
        .name("market-research")
        .version("1.0.0")
        .title("Market Research")
        .summary(
            "Conducts market research via remote A2A agents — gathers intelligence,"
                + " analyses trends, produces assessment")
        .type(Path.parse("intelligence/research"))
        .label(Path.parse("example/a2a"))
        .capabilities(gatherIntelligence, analyseTrends, produceAssessment)
        .workers(
            Worker.builder()
                .name("intel-gatherer")
                .capabilityName("gatherIntelligence")
                .noFunction()
                .build(),
            Worker.builder()
                .name("trend-analyst")
                .capabilityName("analyseTrends")
                .noFunction()
                .build(),
            Worker.builder()
                .name("report-producer")
                .capabilityName("produceAssessment")
                .noFunction()
                .build())
        .bindings(
            Binding.builder()
                .name("gather-on-brief")
                .capability(gatherIntelligence)
                .on(new ContextChangeTrigger(".brief != null and .intelligence == null"))
                .build(),
            Binding.builder()
                .name("analyse-after-gathering")
                .capability(analyseTrends)
                .on(new ContextChangeTrigger(".intelligence != null and .trends == null"))
                .build(),
            Binding.builder()
                .name("assess-after-analysis")
                .capability(produceAssessment)
                .on(new ContextChangeTrigger(".trends != null and .assessment == null"))
                .build())
        .goals(
            Goal.builder()
                .name("researchComplete")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".assessment != null and .assessment.recommendations != null")
                .build())
        .completion(GoalExpression.goal("researchComplete"))
        .build();
  }
}
