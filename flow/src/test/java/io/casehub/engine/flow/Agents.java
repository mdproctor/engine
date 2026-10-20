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

import java.util.List;

public class Agents {

  public record SentimentRequest(String documentId, String content) {}

  public record SentimentResult(String sentiment, double confidence, List<String> keywords) {}

  public interface SentimentAnalysisAgent {

    SentimentResult analyze(String memoryId, SentimentRequest input);
  }

  public record SummaryRequest(String documentId, String content) {}

  public record SummaryResult(String summary, List<String> keyPoints) {}

  public interface ContentSummarizerAgent {

    SummaryResult summarize(String memoryId, SummaryRequest input);
  }
}
