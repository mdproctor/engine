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
package io.casehub.engine.flow;

import static io.serverlessworkflow.fluent.func.FuncWorkflowBuilder.workflow;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.agent;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.get;

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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AgentPipelineBean extends CaseHub {

  @Inject Agents.SentimentAnalysisAgent sentimentAgent;
  @Inject Agents.ContentSummarizerAgent summarizerAgent;

  public void setAgents(
      Agents.SentimentAnalysisAgent sentimentAgent, Agents.ContentSummarizerAgent summarizerAgent) {
    this.sentimentAgent = sentimentAgent;
    this.summarizerAgent = summarizerAgent;
  }

  @Override
  public CaseDefinition getDefinition() {

    // --- Capabilities ---

    Capability fetchCap =
        Capability.builder()
            .name("fetchDocument")
            .inputSchema("{ documentId: .documentId }")
            .outputSchema("{ data: .data, step: .step }")
            .description("Fetch document data from external API")
            .build();

    Capability sentimentCap =
        Capability.builder()
            .name("analyzeSentiment")
            .inputSchema("{ documentId: .documentId, content: .data.content }")
            .outputSchema("{ sentiment: .sentiment, step: .step }")
            .description("Analyze document sentiment using LLM agent")
            .build();

    Capability summaryCap =
        Capability.builder()
            .name("summarizeContent")
            .inputSchema("{ documentId: .documentId, content: .data.content }")
            .outputSchema("{ summary: .summary, step: .step }")
            .description("Summarize document content using LLM agent")
            .build();

    Goal goal =
        Goal.builder()
            .name("reviewComplete")
            .condition(".step == \"summarized\"")
            .kind(GoalKind.SUCCESS)
            .description("Document review is complete with sentiment and summary")
            .build();

    return CaseDefinition.builder()
        .namespace("test")
        .name("Agent Document Review Pipeline")
        .version("1.0.0")
        .title("Three-step document analysis pipeline with HTTP fetch and two agents")
        .capabilities(fetchCap, sentimentCap, summaryCap)
        .workers(
            Worker.builder()
                .name("document-fetcher")
                .capabilityName("fetchDocument")
                .function(
                    new FlowWorkerFunction(
                        workflow("fetch-document")
                            .tasks(
                                get("fetchDocumentData", "http://localhost:8889/fetch/{id}")
                                    .inputFrom("{ id: .documentId }")
                                    .outputAs("{ data: ., step: \"fetched\" }"))
                            .build()))
                .description("Fetches document data from external API")
                .build(),
            Worker.builder()
                .name("sentiment-analyzer")
                .capabilityName("analyzeSentiment")
                .function(
                    new FlowWorkerFunction(
                        workflow("analyze-sentiment")
                            .tasks(
                                agent(sentimentAgent::analyze, Agents.SentimentRequest.class)
                                    .inputFrom(
                                        "{ documentId: .documentId, content: .data.content }")
                                    .outputAs("{ sentiment: ., step: \"analyzed\" }"))
                            .build()))
                .description("Analyzes document sentiment via LLM")
                .build(),
            Worker.builder()
                .name("content-summarizer")
                .capabilityName("summarizeContent")
                .function(
                    new FlowWorkerFunction(
                        workflow("summarize-content")
                            .tasks(
                                agent(summarizerAgent::summarize, Agents.SummaryRequest.class)
                                    .inputFrom(
                                        "{ documentId: .documentId, content: .data.content }")
                                    .outputAs("{ summary: ., step: \"summarized\" }"))
                            .build()))
                .description("Summarizes document content via LLM")
                .build())
        .bindings(
            Binding.builder()
                .name("trigger-on-submitted")
                .capability(fetchCap)
                .on(new ContextChangeTrigger(".step == \"submitted\""))
                .build(),
            Binding.builder()
                .name("trigger-on-fetched")
                .capability(sentimentCap)
                .on(new ContextChangeTrigger(".step == \"fetched\""))
                .build(),
            Binding.builder()
                .name("trigger-on-analyzed")
                .capability(summaryCap)
                .on(new ContextChangeTrigger(".step == \"analyzed\""))
                .build())
        .milestones(
            Milestone.builder()
                .name("documentFetched")
                .completionCriteria(".step == \"fetched\"")
                .description("Document data has been fetched from API")
                .build(),
            Milestone.builder()
                .name("sentimentAnalyzed")
                .completionCriteria(".step == \"analyzed\"")
                .description("Document sentiment has been analyzed")
                .build(),
            Milestone.builder()
                .name("contentSummarized")
                .completionCriteria(".step == \"summarized\"")
                .description("Document content has been summarized")
                .build())
        .goals(goal)
        .completion(GoalExpression.allOf(goal))
        .build();
  }
}
