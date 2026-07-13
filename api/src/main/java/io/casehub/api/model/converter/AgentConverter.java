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
package io.casehub.api.model.converter;

import io.casehub.api.model.ai.AgentBuilder;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.anthropic.AnthropicChatModelProvider;
import io.casehub.api.model.ai.gemini.GoogleAiGeminiChatModelProvider;
import io.casehub.api.model.ai.mistral.MistralAiChatModelProvider;
import io.casehub.api.model.ai.ollama.OllamaChatModelProvider;
import io.casehub.api.model.ai.openai.OpenAiChatModelProvider;
import io.casehub.model.Agent;
import io.casehub.model.AgentModel;
import io.casehub.model.AnthropicModel;
import io.casehub.model.GoogleAiGeminiModel;
import io.casehub.model.MistralAiModel;
import io.casehub.model.OllamaModel;
import io.casehub.model.OpenAiModel;

public class AgentConverter {

  public static io.casehub.api.model.ai.Agent toApiAgent(Agent schemaAgent) {
    if (schemaAgent == null) {
      return null;
    }

    ChatModelProvider modelProvider = toChatModelProvider(schemaAgent.getModel());

    AgentBuilder builder =
        io.casehub.api.model.ai.Agent.builder()
            .systemPrompt(schemaAgent.getSystemPrompt())
            .inputProjection(schemaAgent.getInputProjection())
            .outputProjection(schemaAgent.getOutputProjection())
            .model(modelProvider);

    if (schemaAgent.getUserMessageTemplate() != null) {
      builder.userMessage(schemaAgent.getUserMessageTemplate());
    }

    return builder.build();
  }

  private static ChatModelProvider toChatModelProvider(AgentModel model) {
    if (model == null) {
      throw new IllegalArgumentException("AgentModel is required");
    }

    if (model.getOpenai() != null) {
      return toOpenAiProvider(model.getOpenai());
    } else if (model.getOllama() != null) {
      return toOllamaProvider(model.getOllama());
    } else if (model.getAnthropic() != null) {
      return toAnthropicProvider(model.getAnthropic());
    } else if (model.getMistralAi() != null) {
      return toMistralProvider(model.getMistralAi());
    } else if (model.getGoogleAiGemini() != null) {
      return toGoogleAiProvider(model.getGoogleAiGemini());
    } else {
      throw new IllegalArgumentException("No model provider configured in AgentModel");
    }
  }

  private static ChatModelProvider toOpenAiProvider(OpenAiModel model) {
    OpenAiChatModelProvider.Builder builder =
        OpenAiChatModelProvider.builder().apiKey(model.getApiKey()).modelName(model.getModelName());

    if (model.getBaseUrl() != null) {
      builder.baseUrl(model.getBaseUrl());
    }
    if (model.getOrganizationId() != null) {
      builder.organizationId(model.getOrganizationId());
    }
    if (model.getTemperature() != null) {
      builder.temperature(model.getTemperature());
    }
    if (model.getTopP() != null) {
      builder.topP(model.getTopP());
    }
    if (model.getMaxTokens() != null) {
      builder.maxTokens(model.getMaxTokens());
    }
    if (model.getFrequencyPenalty() != null) {
      builder.frequencyPenalty(model.getFrequencyPenalty());
    }
    if (model.getPresencePenalty() != null) {
      builder.presencePenalty(model.getPresencePenalty());
    }

    return builder.build();
  }

  private static ChatModelProvider toOllamaProvider(OllamaModel model) {
    OllamaChatModelProvider.Builder builder =
        OllamaChatModelProvider.builder()
            .baseUrl(model.getBaseUrl())
            .modelName(model.getModelName());

    if (model.getTemperature() != null) {
      builder.temperature(model.getTemperature());
    }
    if (model.getTopP() != null) {
      builder.topP(model.getTopP());
    }
    // Note: Ollama uses numPredict, not topK - topK is ignored

    return builder.build();
  }

  private static ChatModelProvider toAnthropicProvider(AnthropicModel model) {
    AnthropicChatModelProvider.Builder builder =
        AnthropicChatModelProvider.builder()
            .apiKey(model.getApiKey())
            .modelName(model.getModelName());

    if (model.getBaseUrl() != null) {
      builder.baseUrl(model.getBaseUrl());
    }
    if (model.getVersion() != null) {
      builder.version(model.getVersion());
    }
    if (model.getTemperature() != null) {
      builder.temperature(model.getTemperature());
    }
    if (model.getTopP() != null) {
      builder.topP(model.getTopP());
    }
    if (model.getTopK() != null) {
      builder.topK(model.getTopK());
    }
    if (model.getMaxTokens() != null) {
      builder.maxTokens(model.getMaxTokens());
    }

    return builder.build();
  }

  private static ChatModelProvider toMistralProvider(MistralAiModel model) {
    MistralAiChatModelProvider.Builder builder =
        MistralAiChatModelProvider.builder()
            .apiKey(model.getApiKey())
            .modelName(model.getModelName());

    if (model.getBaseUrl() != null) {
      builder.baseUrl(model.getBaseUrl());
    }
    if (model.getTemperature() != null) {
      builder.temperature(model.getTemperature());
    }
    if (model.getTopP() != null) {
      builder.topP(model.getTopP());
    }
    if (model.getMaxTokens() != null) {
      builder.maxTokens(model.getMaxTokens());
    }

    return builder.build();
  }

  private static ChatModelProvider toGoogleAiProvider(GoogleAiGeminiModel model) {
    GoogleAiGeminiChatModelProvider.Builder builder =
        GoogleAiGeminiChatModelProvider.builder()
            .apiKey(model.getApiKey())
            .modelName(model.getModelName());

    if (model.getTemperature() != null) {
      builder.temperature(model.getTemperature());
    }
    if (model.getTopP() != null) {
      builder.topP(model.getTopP());
    }
    // Note: Google AI Gemini doesn't support topK - topK is ignored
    if (model.getMaxTokens() != null) {
      builder.maxOutputTokens(model.getMaxTokens());
    }

    return builder.build();
  }
}
