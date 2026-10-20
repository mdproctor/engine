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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

public class StubAgents {

  @ApplicationScoped
  public static class StubSentimentAnalysisAgent implements Agents.SentimentAnalysisAgent {

    @Override
    public Agents.SentimentResult analyze(String memoryId, Agents.SentimentRequest input) {
      return new Agents.SentimentResult("NEUTRAL", 0.5, List.of());
    }
  }

  @ApplicationScoped
  public static class StubContentSummarizerAgent implements Agents.ContentSummarizerAgent {

    @Override
    public Agents.SummaryResult summarize(String memoryId, Agents.SummaryRequest input) {
      return new Agents.SummaryResult("No summary available.", List.of());
    }
  }
}
