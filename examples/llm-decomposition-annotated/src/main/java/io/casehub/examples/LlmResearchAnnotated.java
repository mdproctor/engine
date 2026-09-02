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
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Customize;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Worker;
import io.casehub.engine.plan.PlanningConstraints;
import java.time.Duration;
import java.util.Map;

/**
 * LLM Decomposition — Research Analysis (annotation pathway).
 *
 * <p>Annotations cannot express {@code decompositionStrategy}, {@code planningConstraints}, or
 * {@code adaptationConfig} natively. The {@code @Customize} escape hatch drops into the DSL to fill
 * these gaps — showing exactly where annotations reach their limit.
 *
 * <p>See also: examples/yaml/llm-research-analysis.yaml (YAML pathway)
 * examples/llm-decomposition-dsl/ (DSL pathway)
 */
@Case(
    namespace = "research",
    name = "AnalysisPipeline",
    version = "1.0.0",
    title = "Research Analysis Pipeline",
    summary =
        "Analyses a research topic — gathers sources, analyses data,"
            + " synthesises findings using LLM-driven decomposition")
public interface LlmResearchAnnotated {

  @Worker(capability = "gatherSources", description = "AI-powered academic source discovery")
  @Bind(contextChange = ".topic != null and .sources == null")
  default Map<String, Object> gatherSources(Map<String, Object> input) {
    return Map.of(
        "sources",
        Map.of("papers", java.util.List.of(), "datasets", java.util.List.of(), "sourceCount", 5));
  }

  @Worker(capability = "analyseData", description = "AI-powered statistical analysis")
  @Bind(contextChange = ".sources != null and .analysis == null")
  default Map<String, Object> analyseData(Map<String, Object> input) {
    return Map.of(
        "analysis",
        Map.of(
            "findings",
            java.util.List.of("pattern-1"),
            "confidence",
            0.85,
            "methodology",
            "statistical"));
  }

  @Worker(
      capability = "synthesiseFindings",
      description = "AI-powered research synthesis and conclusion generation")
  @Bind(contextChange = ".analysis != null and .synthesis == null")
  default Map<String, Object> synthesiseFindings(Map<String, Object> input) {
    return Map.of(
        "synthesis",
        Map.of(
            "summary", "Research complete",
            "conclusions", java.util.List.of("conclusion-1"),
            "limitations", java.util.List.of("small sample")));
  }

  @Goal(
      value = "Research analysis completed",
      condition = ".synthesis != null and .synthesis.conclusions != null")
  default void analysisComplete() {}

  @Goal(
      value = "Insufficient sources",
      condition = ".sources != null and .sources.sourceCount == 0",
      kind = "FAILURE")
  default void insufficientSources() {}

  /**
   * Annotations cannot express decompositionStrategy, planningConstraints, or adaptationConfig
   * natively. This @Customize block drops into the DSL to fill these gaps.
   */
  @Customize
  static void customize(CaseDefinition.Builder builder) {
    builder
        .decompositionStrategy("llm")
        .planningConstraints(PlanningConstraints.of(Duration.ofHours(1), 3))
        .adaptationConfig(AdaptationConfig.of("every-step", "forward-replan"));
  }
}
