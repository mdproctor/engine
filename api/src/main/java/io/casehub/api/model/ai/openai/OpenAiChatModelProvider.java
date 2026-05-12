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
package io.casehub.api.model.ai.openai;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;

public final class OpenAiChatModelProvider implements ChatModelProvider {

  private final String apiKey;
  private final String modelName;
  private final Double temperature;
  private final Double topP;
  private final Integer maxTokens;
  private final Integer maxCompletionTokens;

  // no-arg constructor for ServiceLoader — reads from env vars
  public OpenAiChatModelProvider() {
    this.apiKey = System.getenv("OPENAI_API_KEY");
    this.modelName = "gpt-4o-mini";
    this.temperature = null;
    this.topP = null;
    this.maxTokens = null;
    this.maxCompletionTokens = null;
  }

  private OpenAiChatModelProvider(Builder b) {
    this.apiKey = b.apiKey;
    this.modelName = b.modelName;
    this.temperature = b.temperature;
    this.topP = b.topP;
    this.maxTokens = b.maxTokens;
    this.maxCompletionTokens = b.maxCompletionTokens;
  }

  @Override
  public ModelType type() {
    return ModelType.OPENAI;
  }

  @Override
  public ChatModel get() {
    try {
      Class<?> modelClass = Class.forName("dev.langchain4j.model.openai.OpenAiChatModel");
      Object openAiBuilder = modelClass.getMethod("builder").invoke(null);
      Class<?> builderClass = openAiBuilder.getClass();

      invoke(builderClass, openAiBuilder, "apiKey", String.class, apiKey);
      invoke(builderClass, openAiBuilder, "modelName", String.class, modelName);
      if (temperature != null)
        invoke(builderClass, openAiBuilder, "temperature", Double.class, temperature);
      if (topP != null) invoke(builderClass, openAiBuilder, "topP", Double.class, topP);
      if (maxTokens != null)
        invoke(builderClass, openAiBuilder, "maxTokens", Integer.class, maxTokens);
      if (maxCompletionTokens != null)
        invoke(
            builderClass, openAiBuilder, "maxCompletionTokens", Integer.class, maxCompletionTokens);

      return (ChatModel) builderClass.getMethod("build").invoke(openAiBuilder);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new AgentException("Failed to build OpenAiChatModel: " + cause.getMessage(), cause);
    } catch (Exception e) {
      throw new AgentException("Failed to build OpenAiChatModel: " + e.getMessage(), e);
    }
  }

  private static void invoke(
      Class<?> builderClass, Object builder, String method, Class<?> type, Object value)
      throws Exception {
    builderClass.getMethod(method, type).invoke(builder, value);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String apiKey;
    private String modelName = "gpt-4o-mini";
    private Double temperature;
    private Double topP;
    private Integer maxTokens;
    private Integer maxCompletionTokens;

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public Builder temperature(double temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder topP(double topP) {
      this.topP = topP;
      return this;
    }

    public Builder maxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    public Builder maxCompletionTokens(int maxCompletionTokens) {
      this.maxCompletionTokens = maxCompletionTokens;
      return this;
    }

    public OpenAiChatModelProvider build() {
      if (apiKey == null) throw new IllegalStateException("apiKey is required");
      return new OpenAiChatModelProvider(this);
    }
  }
}
