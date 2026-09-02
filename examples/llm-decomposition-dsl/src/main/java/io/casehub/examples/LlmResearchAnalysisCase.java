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

import io.casehub.api.model.AdaptationConfig;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.engine.plan.PlanningConstraints;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.time.Duration;

/**
 * LLM Decomposition — Research Analysis (DSL pathway).
 *
 * <p>Same case as {@code examples/yaml/llm-research-analysis.yaml} and {@code
 * examples/llm-decomposition-annotated/}. An LLM decomposes the high-level goal into concrete steps
 * based on available capabilities.
 *
 * <p>See also: examples/yaml/llm-research-analysis.yaml (YAML pathway)
 * examples/llm-decomposition-annotated/ (annotation pathway)
 */
public final class LlmResearchAnalysisCase {

  private LlmResearchAnalysisCase() {}

  public static CaseDefinition define() {
    Capability gatherSources =
        Capability.of(
            "gatherSources",
            "{ topic: .topic, keywords: .keywords }",
            "{ sources: { papers: .papers, datasets: .datasets, sourceCount: .sourceCount } }");

    Capability analyseData =
        Capability.of(
            "analyseData",
            "{ sources: .sources, methodology: .methodology }",
            "{ analysis: { findings: .findings, confidence: .confidence,"
                + " methodology: .methodology } }");

    Capability synthesiseFindings =
        Capability.of(
            "synthesiseFindings",
            "{ analysis: .analysis, sources: .sources, topic: .topic }",
            "{ synthesis: { summary: .summary, conclusions: .conclusions,"
                + " limitations: .limitations } }");

    return CaseDefinition.builder()
        .namespace("research")
        .name("analysis-pipeline")
        .version("1.0.0")
        .title("Research Analysis Pipeline")
        .summary(
            "Analyses a research topic — gathers sources, analyses data,"
                + " synthesises findings using LLM-driven decomposition")
        .type(Path.parse("research/analysis"))
        .label(Path.parse("example/llm-decomposition"))
        .decompositionStrategy("llm")
        .planningConstraints(PlanningConstraints.of(Duration.ofHours(1), 3))
        .adaptationConfig(AdaptationConfig.of("every-step", "forward-replan"))
        .capabilities(gatherSources, analyseData, synthesiseFindings)
        .workers(
            Worker.builder()
                .name("source-gatherer")
                .capabilityName("gatherSources")
                .noFunction()
                .build(),
            Worker.builder()
                .name("data-analyst")
                .capabilityName("analyseData")
                .noFunction()
                .build(),
            Worker.builder()
                .name("findings-synthesiser")
                .capabilityName("synthesiseFindings")
                .noFunction()
                .build())
        .bindings(
            Binding.builder()
                .name("gather-on-topic")
                .capability(gatherSources)
                .on(new ContextChangeTrigger(".topic != null and .sources == null"))
                .build(),
            Binding.builder()
                .name("analyse-after-gathering")
                .capability(analyseData)
                .on(new ContextChangeTrigger(".sources != null and .analysis == null"))
                .build(),
            Binding.builder()
                .name("synthesise-after-analysis")
                .capability(synthesiseFindings)
                .on(new ContextChangeTrigger(".analysis != null and .synthesis == null"))
                .build())
        .goals(
            Goal.builder()
                .name("analysisComplete")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".synthesis != null and .synthesis.conclusions != null")
                .build(),
            Goal.builder()
                .name("insufficientSources")
                .kind(StandardGoalKind.FAILURE)
                .condition(".sources != null and .sources.sourceCount == 0")
                .build())
        .completion(
            GoalExpression.goal("analysisComplete"), GoalExpression.goal("insufficientSources"))
        .build();
  }
}
