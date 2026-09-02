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
 * MCP — Code Analysis (DSL pathway).
 *
 * <p>Same case as {@code examples/yaml/mcp-code-analysis.yaml} and {@code examples/mcp-annotated/}.
 * MCP servers provide static analysis and vulnerability scanning via tool discovery. In the DSL
 * pathway, workers use {@code noFunction()} — the {@code mcp:} block is YAML-specific configuration
 * wired at deployment time.
 *
 * <p>See also: examples/yaml/mcp-code-analysis.yaml (YAML pathway) examples/mcp-annotated/
 * (annotation pathway)
 */
public final class McpCodeAnalysisCase {

  private McpCodeAnalysisCase() {}

  public static CaseDefinition define() {
    Capability staticAnalysis =
        Capability.of(
            "staticAnalysis",
            "{ repository: .repository, branch: .branch }",
            "{ staticReport: { issues: .issues, metrics: .metrics, score: .score } }");

    Capability vulnerabilityScan =
        Capability.of(
            "vulnerabilityScan",
            "{ repository: .repository, branch: .branch }",
            "{ vulnReport: { vulnerabilities: .vulnerabilities, severity: .severity,"
                + " patchable: .patchable } }");

    Capability generateReport =
        Capability.of(
            "generateReport",
            "{ staticReport: .staticReport, vulnReport: .vulnReport,"
                + " repository: .repository }",
            "{ report: { summary: .summary, grade: .grade," + " actionItems: .actionItems } }");

    return CaseDefinition.builder()
        .namespace("devops")
        .name("code-analysis")
        .version("1.0.0")
        .title("Code Analysis")
        .summary(
            "Analyses a codebase using MCP tools — static analysis,"
                + " vulnerability scanning, unified reporting")
        .type(Path.parse("devops/analysis"))
        .label(Path.parse("example/mcp"))
        .capabilities(staticAnalysis, vulnerabilityScan, generateReport)
        .workers(
            Worker.builder()
                .name("static-analyser")
                .capabilityName("staticAnalysis")
                .noFunction()
                .build(),
            Worker.builder()
                .name("vuln-scanner")
                .capabilityName("vulnerabilityScan")
                .noFunction()
                .build(),
            Worker.builder()
                .name("report-generator")
                .capabilityName("generateReport")
                .noFunction()
                .build())
        .bindings(
            Binding.builder()
                .name("analyse-on-push")
                .capability(staticAnalysis)
                .on(new ContextChangeTrigger(".repository != null and .staticReport == null"))
                .build(),
            Binding.builder()
                .name("scan-on-push")
                .capability(vulnerabilityScan)
                .on(new ContextChangeTrigger(".repository != null and .vulnReport == null"))
                .build(),
            Binding.builder()
                .name("report-after-scans")
                .capability(generateReport)
                .on(
                    new ContextChangeTrigger(
                        ".staticReport != null and .vulnReport != null" + " and .report == null"))
                .build())
        .goals(
            Goal.builder()
                .name("analysisComplete")
                .kind(StandardGoalKind.SUCCESS)
                .condition(".report != null and .report.grade != null")
                .build())
        .completion(GoalExpression.goal("analysisComplete"))
        .build();
  }
}
