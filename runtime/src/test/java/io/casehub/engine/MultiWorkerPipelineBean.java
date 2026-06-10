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
package io.casehub.engine;

import static io.serverlessworkflow.fluent.func.FuncWorkflowBuilder.workflow;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.function;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MultiWorkerPipelineBean extends CaseHub {

  @Override
  public CaseDefinition getDefinition() {

    Capability validateCap =
        Capability.builder()
            .name("validateDocument")
            .inputSchema("{ documentId: .working.documentId, step: .working.step }")
            .outputSchema("{ valid: .valid, step: .step }")
            .description("Validate a received document")
            .build();

    Capability enrichCap =
        Capability.builder()
            .name("enrichDocument")
            .inputSchema("{ documentId: .working.documentId, valid: .working.valid }")
            .outputSchema("{ enrichedData: .enrichedData, step: .step }")
            .description("Enrich a validated document with metadata")
            .build();

    Capability publishCap =
        Capability.builder()
            .name("publishDocument")
            .inputSchema("{ documentId: .working.documentId, enrichedData: .working.enrichedData }")
            .outputSchema("{ publishedUrl: .publishedUrl, step: .step }")
            .description("Publish an enriched document")
            .build();

    Goal goal =
        Goal.builder()
            .name("pipelineComplete")
            .condition(".working.step == \"published\"")
            .kind(GoalKind.SUCCESS)
            .description("All pipeline steps completed successfully")
            .build();

    return CaseDefinition.builder()
        .namespace("test")
        .name("Multi-Worker Document Pipeline")
        .version("1.0.0")
        .title("Three-step document processing pipeline")
        .capabilities(validateCap, enrichCap, publishCap)
        .workers(
            Worker.builder()
                .name("document-validator")
                .capabilities(validateCap)
                .function(
                    workflow("validate-document")
                        .tasks(
                            function(
                                s -> {
                                  Map<String, Object> ctx = (Map<String, Object>) s;
                                  if (!ctx.containsKey("documentId")) {
                                    throw new RuntimeException("Missing documentId");
                                  }
                                  return Map.of("valid", true, "step", "validated");
                                },
                                Map.class))
                        .build())
                .description("Validates incoming documents")
                .build(),
            Worker.builder()
                .name("document-enricher")
                .capabilities(enrichCap)
                .function(
                    workflow("enrich-document")
                        .tasks(
                            function(
                                s -> {
                                  Map<String, Object> ctx = (Map<String, Object>) s;
                                  if (!ctx.containsKey("documentId")) {
                                    throw new RuntimeException("Missing documentId");
                                  }
                                  return Map.of(
                                      "enrichedData",
                                      Map.of(
                                          "source", "internal",
                                          "tags", List.of("validated", "enriched"),
                                          "documentId", ctx.get("documentId")),
                                      "step",
                                      "enriched");
                                },
                                Map.class))
                        .build())
                .description("Enriches validated documents with metadata")
                .build(),
            Worker.builder()
                .name("document-publisher")
                .capabilities(publishCap)
                .function(
                    workflow("publish-document")
                        .tasks(
                            function(
                                s -> {
                                  Map<String, Object> ctx = (Map<String, Object>) s;
                                  if (!ctx.containsKey("documentId")) {
                                    throw new RuntimeException("Missing documentId");
                                  }
                                  return Map.of(
                                      "publishedUrl",
                                      "https://docs.example.com/" + ctx.get("documentId"),
                                      "step",
                                      "published");
                                },
                                Map.class))
                        .build())
                .description("Publishes enriched documents")
                .build())
        .bindings(
            Binding.builder()
                .name("trigger-on-received")
                .capability(validateCap)
                .on(new ContextChangeTrigger(".working.step == \"received\""))
                .build(),
            Binding.builder()
                .name("trigger-on-validated")
                .capability(enrichCap)
                .on(
                    new ContextChangeTrigger(
                        ".working.step == \"validated\" and .working.valid == true"))
                .build(),
            Binding.builder()
                .name("trigger-on-enriched")
                .capability(publishCap)
                .on(new ContextChangeTrigger(".working.step == \"enriched\""))
                .build())
        .milestones(
            Milestone.builder()
                .name("documentValidated")
                .completionCriteria(".working.step == \"validated\"")
                .description("Document has been validated")
                .build(),
            Milestone.builder()
                .name("documentEnriched")
                .completionCriteria(".working.step == \"enriched\"")
                .description("Document has been enriched")
                .build(),
            Milestone.builder()
                .name("documentPublished")
                .completionCriteria(".working.step == \"published\"")
                .description("Document has been published")
                .build())
        .goals(goal)
        .completion(GoalExpression.allOf(goal))
        .build();
  }
}
