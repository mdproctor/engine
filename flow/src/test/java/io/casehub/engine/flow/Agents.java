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

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.List;

public class Agents {

  public record SentimentRequest(String documentId, String content) {}

  public record SentimentResult(String sentiment, double confidence, List<String> keywords) {}

  @RegisterAiService
  @SystemMessage(
      """
            You are an expert sentiment analysis specialist.

            Given:
            - a document ID
            - the document content text,

            you MUST respond with a short JSON document that can be mapped to:
              SentimentResult {
                String sentiment;        // POSITIVE, NEGATIVE or NEUTRAL
                double confidence;       // 0.0 to 1.0
                List<String> keywords;   // up to 5 key terms driving the sentiment
              }

            Be precise and objective. Base your analysis strictly on the provided text.
            """)
  public interface SentimentAnalysisAgent {

    @UserMessage(
        """
                Document ID: {data.documentId}

                Here is the document content to analyze:

                {data.content}

                Produce a SentimentResult JSON as specified above.
                """)
    SentimentResult analyze(@MemoryId String memoryId, @V("data") SentimentRequest input);
  }

  public record SummaryRequest(String documentId, String content) {}

  public record SummaryResult(String summary, List<String> keyPoints) {}

  @RegisterAiService
  @SystemMessage(
      """
            You are a concise and accurate document summarizer.

            Given:
            - a document ID
            - the document content text,

            you MUST respond with a short JSON document that can be mapped to:
              SummaryResult {
                String summary;            // 1-3 sentence summary
                List<String> keyPoints;    // up to 5 key takeaways
              }

            Be factual. Do not add opinions or information not present in the source text.
            """)
  public interface ContentSummarizerAgent {

    @UserMessage(
        """
                Document ID: {data.documentId}

                Here is the document content to summarize:

                {data.content}

                Produce a SummaryResult JSON as specified above.
                """)
    SummaryResult summarize(@MemoryId String memoryId, @V("data") SummaryRequest input);
  }
}
