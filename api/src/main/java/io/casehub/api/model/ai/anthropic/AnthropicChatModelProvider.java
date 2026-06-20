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
package io.casehub.api.model.ai.anthropic;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;

public final class AnthropicChatModelProvider implements ChatModelProvider {

  private final String apiKey;
  private final String modelName;
  private final String baseUrl;
  private final String version;
  private final Double temperature;
  private final Double topP;
  private final Integer topK;
  private final Integer maxTokens;

  // no-arg constructor for ServiceLoader — reads from env vars
  public AnthropicChatModelProvider() {
    this.apiKey = System.getenv("ANTHROPIC_API_KEY");
    this.modelName = "claude-3-5-sonnet-20241022";
    this.baseUrl = null;
    this.version = null;
    this.temperature = null;
    this.topP = null;
    this.topK = null;
    this.maxTokens = null;
  }

  private AnthropicChatModelProvider(Builder b) {
    this.apiKey = b.apiKey;
    this.modelName = b.modelName;
    this.baseUrl = b.baseUrl;
    this.version = b.version;
    this.temperature = b.temperature;
    this.topP = b.topP;
    this.topK = b.topK;
    this.maxTokens = b.maxTokens;
  }

  @Override
  public ModelType type() {
    return ModelType.ANTHROPIC;
  }

  @Override
  public ChatModel get() {
    try {
      Class<?> modelClass = Class.forName("dev.langchain4j.model.anthropic.AnthropicChatModel");
      Object anthropicBuilder = modelClass.getMethod("builder").invoke(null);
      Class<?> builderClass = anthropicBuilder.getClass();

      invoke(builderClass, anthropicBuilder, "apiKey", String.class, apiKey);
      invoke(builderClass, anthropicBuilder, "modelName", String.class, modelName);
      if (baseUrl != null) invoke(builderClass, anthropicBuilder, "baseUrl", String.class, baseUrl);
      if (version != null) invoke(builderClass, anthropicBuilder, "version", String.class, version);
      if (temperature != null)
        invoke(builderClass, anthropicBuilder, "temperature", Double.class, temperature);
      if (topP != null) invoke(builderClass, anthropicBuilder, "topP", Double.class, topP);
      if (topK != null) invoke(builderClass, anthropicBuilder, "topK", Integer.class, topK);
      if (maxTokens != null)
        invoke(builderClass, anthropicBuilder, "maxTokens", Integer.class, maxTokens);

      return (ChatModel) builderClass.getMethod("build").invoke(anthropicBuilder);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new AgentException("Failed to build AnthropicChatModel: " + cause.getMessage(), cause);
    } catch (Exception e) {
      throw new AgentException("Failed to build AnthropicChatModel: " + e.getMessage(), e);
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
    private String modelName = "claude-3-5-sonnet-20241022";
    private String baseUrl;
    private String version;
    private Double temperature;
    private Double topP;
    private Integer topK;
    private Integer maxTokens;

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder version(String version) {
      this.version = version;
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

    public Builder topK(int topK) {
      this.topK = topK;
      return this;
    }

    public Builder maxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    public AnthropicChatModelProvider build() {
      if (apiKey == null) throw new IllegalStateException("apiKey is required");
      return new AnthropicChatModelProvider(this);
    }
  }
}
