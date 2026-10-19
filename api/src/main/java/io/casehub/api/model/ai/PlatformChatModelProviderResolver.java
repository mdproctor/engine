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

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * {@link ChatModelProviderResolver} that delegates to a platform-provided {@link ChatModel}
 * (typically the routing ChatModel from casehub-platform). The YAML {@code modelName} is injected
 * into every {@link ChatRequest} so the routing layer can resolve the correct backend.
 *
 * <p>Replaces {@link InlineChatModelProviderResolver} when platform ChatModel routing is available.
 */
public final class PlatformChatModelProviderResolver implements ChatModelProviderResolver {

  private final ChatModel chatModel;

  public PlatformChatModelProviderResolver(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  @Override
  public ChatModelProvider resolve(String providerType, JsonNode config) {
    if (providerType == null) {
      throw new IllegalArgumentException("agent 'model' field (provider type) is required");
    }
    String modelName = config.has("modelName") ? config.get("modelName").asText() : null;
    ModelType type = toModelType(providerType);
    ChatModel effective =
        modelName != null ? new ModelNameInjectingChatModel(chatModel, modelName) : chatModel;
    return new DelegatingChatModelProvider(type, effective);
  }

  private static ModelType toModelType(String providerType) {
    return switch (providerType.toLowerCase()) {
      case "openai" -> ModelType.OPENAI;
      case "anthropic" -> ModelType.ANTHROPIC;
      case "ollama" -> ModelType.OLLAMA;
      case "mistralai", "mistral" -> ModelType.MISTRAL;
      case "googleaigemini", "gemini" -> ModelType.GOOGLE_AI_GEMINI;
      default -> throw new IllegalArgumentException("Unknown model provider: " + providerType);
    };
  }

  private record DelegatingChatModelProvider(ModelType type, ChatModel model)
      implements ChatModelProvider {
    @Override
    public ChatModel get() {
      return model;
    }
  }

  private record ModelNameInjectingChatModel(ChatModel delegate, String modelName)
      implements ChatModel {
    @Override
    public ChatResponse chat(ChatRequest request) {
      if (request.parameters().modelName() != null) {
        return delegate.chat(request);
      }
      ChatRequestParameters params =
          request
              .parameters()
              .overrideWith(DefaultChatRequestParameters.builder().modelName(modelName).build());
      ChatRequest modified =
          ChatRequest.builder().messages(request.messages()).parameters(params).build();
      return delegate.chat(modified);
    }
  }
}
