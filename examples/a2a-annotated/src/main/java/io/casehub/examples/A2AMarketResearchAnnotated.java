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

import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Worker;
import java.util.Map;

/**
 * A2A — Market Research (annotation pathway).
 *
 * <p>Annotations cannot express {@code a2a:} worker blocks natively — they generate in-process
 * worker functions. For remote A2A agents, use YAML ({@code a2a:} block) or DSL with the
 * {@code casehub-engine-a2a} module. This annotated version demonstrates the case structure with
 * placeholder in-process workers.
 *
 * <p>See also: examples/yaml/a2a-market-research.yaml (YAML pathway)
 * examples/a2a-dsl/ (DSL pathway)
 */
@Case(
    namespace = "intelligence",
    name = "MarketResearch",
    version = "1.0.0",
    title = "Market Research",
    summary =
        "Conducts market research via remote A2A agents — gathers intelligence,"
            + " analyses trends, produces assessment")
public interface A2AMarketResearchAnnotated {

  @Worker(capability = "gatherIntelligence", description = "Competitive intelligence gathering")
  @Bind(contextChange = ".brief != null and .intelligence == null")
  default Map<String, Object> gatherIntelligence(Map<String, Object> input) {
    return Map.of(
        "intelligence",
        Map.of(
            "sources", java.util.List.of("source-1"),
            "insights", java.util.List.of("insight-1"),
            "confidence", 0.9));
  }

  @Worker(capability = "analyseTrends", description = "Market trend analysis")
  @Bind(contextChange = ".intelligence != null and .trends == null")
  default Map<String, Object> analyseTrends(Map<String, Object> input) {
    return Map.of(
        "trends",
        Map.of(
            "patterns", java.util.List.of("upward"),
            "forecast", "positive",
            "risks", java.util.List.of("regulation")));
  }

  @Worker(capability = "produceAssessment", description = "Assessment report generation")
  @Bind(contextChange = ".trends != null and .assessment == null")
  default Map<String, Object> produceAssessment(Map<String, Object> input) {
    return Map.of(
        "assessment",
        Map.of(
            "summary", "Market outlook positive",
            "recommendations", java.util.List.of("expand"),
            "confidence", 0.85));
  }

  @Goal(
      value = "Market research assessment delivered",
      condition = ".assessment != null and .assessment.recommendations != null")
  default void researchComplete() {}
}
