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
package io.casehub.api.model.ai.ollama;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;

public final class OllamaChatModelProvider implements ChatModelProvider {

  private final String baseUrl;
  private final String modelName;
  private final Double temperature;
  private final Double topP;
  private final Integer numPredict;

  // no-arg constructor for ServiceLoader — reads from env vars
  public OllamaChatModelProvider() {
    String envBaseUrl = System.getenv("OLLAMA_BASE_URL");
    this.baseUrl = envBaseUrl != null ? envBaseUrl : "http://localhost:11434";
    this.modelName = System.getenv("OLLAMA_MODEL");
    this.temperature = null;
    this.topP = null;
    this.numPredict = null;
  }

  private OllamaChatModelProvider(Builder b) {
    this.baseUrl = b.baseUrl;
    this.modelName = b.modelName;
    this.temperature = b.temperature;
    this.topP = b.topP;
    this.numPredict = b.numPredict;
  }

  @Override
  public ModelType type() {
    return ModelType.OLLAMA;
  }

  @Override
  public ChatModel get() {
    try {
      Class<?> modelClass = Class.forName("dev.langchain4j.model.ollama.OllamaChatModel");
      Object ollamaBuilder = modelClass.getMethod("builder").invoke(null);
      Class<?> builderClass = ollamaBuilder.getClass();

      invoke(builderClass, ollamaBuilder, "baseUrl", String.class, baseUrl);
      invoke(builderClass, ollamaBuilder, "modelName", String.class, modelName);
      if (temperature != null)
        invoke(builderClass, ollamaBuilder, "temperature", Double.class, temperature);
      if (topP != null) invoke(builderClass, ollamaBuilder, "topP", Double.class, topP);
      if (numPredict != null)
        invoke(builderClass, ollamaBuilder, "numPredict", Integer.class, numPredict);

      return (ChatModel) builderClass.getMethod("build").invoke(ollamaBuilder);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new AgentException("Failed to build OllamaChatModel: " + cause.getMessage(), cause);
    } catch (Exception e) {
      throw new AgentException("Failed to build OllamaChatModel: " + e.getMessage(), e);
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
    private String baseUrl = "http://localhost:11434";
    private String modelName;
    private Double temperature;
    private Double topP;
    private Integer numPredict;

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
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

    public Builder numPredict(int numPredict) {
      this.numPredict = numPredict;
      return this;
    }

    public OllamaChatModelProvider build() {
      if (modelName == null) throw new IllegalStateException("modelName is required");
      return new OllamaChatModelProvider(this);
    }
  }
}
