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
package io.casehub.api.model.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.input.PromptTemplate;
import io.casehub.worker.api.PlannedAction;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class Agent {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final String systemPrompt;
  private final String userMessageTemplate;
  private final UnaryOperator<JsonNode> inputTransformer;
  private final UnaryOperator<JsonNode> outputTransformer;
  private final ChatModel model;
  private final JsonSchema responseSchema;
  private final Function<Map<String, Object>, PlannedAction> plannedActionExtractor;

  Agent(
      String systemPrompt,
      String userMessageTemplate,
      UnaryOperator<JsonNode> inputTransformer,
      UnaryOperator<JsonNode> outputTransformer,
      ChatModel model,
      JsonSchema responseSchema,
      Function<Map<String, Object>, PlannedAction> plannedActionExtractor) {
    this.systemPrompt = systemPrompt;
    this.userMessageTemplate = userMessageTemplate;
    this.inputTransformer = inputTransformer;
    this.outputTransformer = outputTransformer;
    this.model = model;
    this.responseSchema = responseSchema;
    this.plannedActionExtractor = plannedActionExtractor;
  }

  public static AgentBuilder builder() {
    return new AgentBuilder();
  }

  /**
   * Executes this agent with the given input and returns a {@link WorkerResult}.
   *
   * <p>The output map is the LLM response after applying the output transformer. When a {@code
   * plannedActionExtractor} is configured and returns a non-null {@link PlannedAction}, the result
   * carries the action for downstream risk classification via {@link WorkerResult#of(Object,
   * PlannedAction)}.
   */
  public WorkerResult<Map<String, Object>> execute(Map<String, Object> input) {
    return executeDetailed(input).result();
  }

  /**
   * Executes this agent and returns an {@link AgentResponse} containing both the {@link
   * WorkerResult} and {@link TokenUsage} from the LLM call. Token usage is null when the model does
   * not report it.
   */
  public AgentResponse executeDetailed(Map<String, Object> input) {
    JsonNode inputNode = MAPPER.convertValue(input, JsonNode.class);
    JsonNode transformed = inputTransformer.apply(inputNode);

    String userText;
    if (userMessageTemplate != null) {
      try {
        Map<String, Object> variables = MAPPER.convertValue(transformed, MAP_TYPE);
        userText = PromptTemplate.from(userMessageTemplate).apply(variables).text();
      } catch (Exception e) {
        throw new AgentException("Failed to apply userMessage template: " + e.getMessage(), e);
      }
    } else {
      userText = transformed.toString();
    }

    ChatRequest request =
        ChatRequest.builder()
            .messages(List.of(SystemMessage.from(systemPrompt), UserMessage.from(userText)))
            .responseFormat(buildResponseFormat())
            .build();

    ChatResponse response = model.chat(request);
    String responseText = response.aiMessage().text();

    if (responseText == null || responseText.isEmpty()) {
      throw new AgentException("LLM returned empty response");
    }

    JsonNode responseJson;
    try {
      responseJson = MAPPER.readTree(responseText);
    } catch (Exception e) {
      throw new AgentException("LLM returned invalid JSON: " + responseText, e);
    }

    Map<String, Object> output =
        MAPPER.convertValue(outputTransformer.apply(responseJson), MAP_TYPE);

    WorkerResult<Map<String, Object>> workerResult;
    if (plannedActionExtractor != null) {
      PlannedAction action = plannedActionExtractor.apply(output);
      workerResult = action != null ? WorkerResult.of(output, action) : WorkerResult.of(output);
    } else {
      workerResult = WorkerResult.of(output);
    }

    dev.langchain4j.model.output.TokenUsage lc4jUsage = response.tokenUsage();
    TokenUsage tokenUsage =
        lc4jUsage != null
            ? new TokenUsage(
                lc4jUsage.inputTokenCount() != null ? lc4jUsage.inputTokenCount() : 0,
                lc4jUsage.outputTokenCount() != null ? lc4jUsage.outputTokenCount() : 0)
            : null;

    return new AgentResponse(workerResult, tokenUsage);
  }

  private ResponseFormat buildResponseFormat() {
    ResponseFormat.Builder builder = ResponseFormat.builder().type(ResponseFormatType.JSON);
    if (responseSchema != null) {
      builder.jsonSchema(responseSchema);
    }
    return builder.build();
  }
}
