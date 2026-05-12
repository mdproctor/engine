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
package io.casehub.api.model.ai.gemini;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;

public final class GoogleAiGeminiChatModelProvider implements ChatModelProvider {

  private final String apiKey;
  private final String modelName;
  private final Double temperature;
  private final Double topP;
  private final Integer maxOutputTokens;

  // no-arg constructor for ServiceLoader — reads from env vars
  public GoogleAiGeminiChatModelProvider() {
    this.apiKey = System.getenv("GOOGLE_API_KEY");
    this.modelName = "gemini-2.0-flash";
    this.temperature = null;
    this.topP = null;
    this.maxOutputTokens = null;
  }

  private GoogleAiGeminiChatModelProvider(Builder b) {
    this.apiKey = b.apiKey;
    this.modelName = b.modelName;
    this.temperature = b.temperature;
    this.topP = b.topP;
    this.maxOutputTokens = b.maxOutputTokens;
  }

  @Override
  public ModelType type() {
    return ModelType.GOOGLE_AI_GEMINI;
  }

  @Override
  public ChatModel get() {
    try {
      Class<?> modelClass = Class.forName("dev.langchain4j.model.googleai.GoogleAiGeminiChatModel");
      Object geminiBuilder = modelClass.getMethod("builder").invoke(null);
      Class<?> builderClass = geminiBuilder.getClass();

      invoke(builderClass, geminiBuilder, "apiKey", String.class, apiKey);
      invoke(builderClass, geminiBuilder, "modelName", String.class, modelName);
      if (temperature != null)
        invoke(builderClass, geminiBuilder, "temperature", Double.class, temperature);
      if (topP != null) invoke(builderClass, geminiBuilder, "topP", Double.class, topP);
      if (maxOutputTokens != null)
        invoke(builderClass, geminiBuilder, "maxOutputTokens", Integer.class, maxOutputTokens);

      return (ChatModel) builderClass.getMethod("build").invoke(geminiBuilder);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new AgentException(
          "Failed to build GoogleAiGeminiChatModel: " + cause.getMessage(), cause);
    } catch (Exception e) {
      throw new AgentException("Failed to build GoogleAiGeminiChatModel: " + e.getMessage(), e);
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
    private String modelName = "gemini-2.0-flash";
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;

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

    public Builder maxOutputTokens(int maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    public GoogleAiGeminiChatModelProvider build() {
      if (apiKey == null) throw new IllegalStateException("apiKey is required");
      return new GoogleAiGeminiChatModelProvider(this);
    }
  }
}
