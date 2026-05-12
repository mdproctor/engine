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
package io.casehub.api.model.ai.mistral;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;

public final class MistralAiChatModelProvider implements ChatModelProvider {

  private final String apiKey;
  private final String modelName;
  private final Double temperature;
  private final Double topP;
  private final Integer maxTokens;

  // no-arg constructor for ServiceLoader — reads from env vars
  public MistralAiChatModelProvider() {
    this.apiKey = System.getenv("MISTRAL_API_KEY");
    this.modelName = "mistral-small-latest";
    this.temperature = null;
    this.topP = null;
    this.maxTokens = null;
  }

  private MistralAiChatModelProvider(Builder b) {
    this.apiKey = b.apiKey;
    this.modelName = b.modelName;
    this.temperature = b.temperature;
    this.topP = b.topP;
    this.maxTokens = b.maxTokens;
  }

  @Override
  public ModelType type() {
    return ModelType.MISTRAL;
  }

  @Override
  public ChatModel get() {
    try {
      Class<?> modelClass = Class.forName("dev.langchain4j.model.mistralai.MistralAiChatModel");
      Object mistralBuilder = modelClass.getMethod("builder").invoke(null);
      Class<?> builderClass = mistralBuilder.getClass();

      invoke(builderClass, mistralBuilder, "apiKey", String.class, apiKey);
      invoke(builderClass, mistralBuilder, "modelName", String.class, modelName);
      if (temperature != null)
        invoke(builderClass, mistralBuilder, "temperature", Double.class, temperature);
      if (topP != null) invoke(builderClass, mistralBuilder, "topP", Double.class, topP);
      if (maxTokens != null)
        invoke(builderClass, mistralBuilder, "maxTokens", Integer.class, maxTokens);

      return (ChatModel) builderClass.getMethod("build").invoke(mistralBuilder);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new AgentException("Failed to build MistralAiChatModel: " + cause.getMessage(), cause);
    } catch (Exception e) {
      throw new AgentException("Failed to build MistralAiChatModel: " + e.getMessage(), e);
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
    private String modelName = "mistral-small-latest";
    private Double temperature;
    private Double topP;
    private Integer maxTokens;

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

    public MistralAiChatModelProvider build() {
      if (apiKey == null) throw new IllegalStateException("apiKey is required");
      return new MistralAiChatModelProvider(this);
    }
  }
}
