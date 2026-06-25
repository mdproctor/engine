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

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class SimpleCaseHubBean extends CaseHub {

  @Override
  public CaseDefinition getDefinition() {

    Capability capability =
        Capability.builder()
            .name("processDocument")
            .inputSchema("{ documentId: .documentId, status: .status }")
            .outputSchema("{ processedDocument: ., status: .status }")
            .description("Process a document from the case context")
            .build();

    Goal goal =
        Goal.builder()
            .name("documentProcessingComplete")
            .condition(".status == \"processed\"")
            .kind(GoalKind.SUCCESS)
            .description("Goal achieved when document processing is complete")
            .build();

    return CaseDefinition.builder()
        .namespace("test")
        .name("Document Processing Test")
        .version("1.0.0")
        .title("Test Case with Worker and Capability")
        .capabilities(capability)
        .workers(
            Worker.builder()
                .name("document-processor")
                .capabilities(capability)
                .function(
                    input -> {
                      if (!input.containsKey("documentId")) {
                        throw new RuntimeException("Missing documentId in context");
                      }
                      if (!input.containsKey("status")) {
                        throw new RuntimeException("Missing status in context");
                      }
                      return WorkerResult.of(
                          Map.of(
                              "processedDocument",
                              Map.of(
                                  "id",
                                  input.get("documentId"),
                                  "content",
                                  "Processed content for document " + input.get("documentId")),
                              "status",
                              "processed"));
                    })
                .description("Processes documents and updates case context")
                .build())
        .bindings(
            Binding.builder()
                .name("trigger-on-processing-status")
                .capability(capability)
                .on(new ContextChangeTrigger(".status == \"processing\""))
                .build())
        .milestones(
            Milestone.builder()
                .name("documentProcessed")
                .completionCriteria(".status == \"processed\"")
                .description("Milestone reached when document is processed")
                .build())
        .goals(goal)
        .completion(GoalExpression.allOf(goal))
        .build();
  }
}
