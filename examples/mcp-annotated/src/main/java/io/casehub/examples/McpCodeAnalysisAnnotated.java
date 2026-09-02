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
 * MCP — Code Analysis (annotation pathway).
 *
 * <p>Annotations cannot express {@code mcp:} worker blocks natively — they generate in-process
 * worker functions. For MCP tool invocation, use YAML ({@code mcp:} block) or DSL with the
 * {@code casehub-engine-mcp} module. This annotated version demonstrates the case structure with
 * placeholder in-process workers.
 *
 * <p>See also: examples/yaml/mcp-code-analysis.yaml (YAML pathway)
 * examples/mcp-dsl/ (DSL pathway)
 */
@Case(
    namespace = "devops",
    name = "CodeAnalysis",
    version = "1.0.0",
    title = "Code Analysis",
    summary =
        "Analyses a codebase using MCP tools — static analysis,"
            + " vulnerability scanning, unified reporting")
public interface McpCodeAnalysisAnnotated {

  @Worker(capability = "staticAnalysis", description = "Static code analysis")
  @Bind(contextChange = ".repository != null and .staticReport == null")
  default Map<String, Object> staticAnalysis(Map<String, Object> input) {
    return Map.of(
        "staticReport",
        Map.of(
            "issues", java.util.List.of(),
            "metrics", Map.of("complexity", 12),
            "score", 85));
  }

  @Worker(capability = "vulnerabilityScan", description = "Vulnerability scanning")
  @Bind(contextChange = ".repository != null and .vulnReport == null")
  default Map<String, Object> vulnerabilityScan(Map<String, Object> input) {
    return Map.of(
        "vulnReport",
        Map.of(
            "vulnerabilities", java.util.List.of(),
            "severity", "LOW",
            "patchable", true));
  }

  @Worker(capability = "generateReport", description = "Unified report generation")
  @Bind(contextChange = ".staticReport != null and .vulnReport != null and .report == null")
  default Map<String, Object> generateReport(Map<String, Object> input) {
    return Map.of(
        "report",
        Map.of(
            "summary", "Code quality assessment complete",
            "grade", "A",
            "actionItems", java.util.List.of()));
  }

  @Goal(
      value = "Code analysis report generated",
      condition = ".report != null and .report.grade != null")
  default void analysisComplete() {}
}
