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

import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.Collection;
import java.util.Objects;
import java.util.ServiceLoader;

public final class AgentBuilder {

  private String systemPrompt;
  private String inputSchema;
  private String outputSchema;
  private String userMessageTemplate;
  private ModelType modelType;
  private ChatModel model;
  private ChatModelProvider chatModelProvider;
  private JsonSchema responseSchema;

  AgentBuilder() {}

  public AgentBuilder systemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
    return this;
  }

  public AgentBuilder inputSchema(String jqExpression) {
    this.inputSchema = jqExpression;
    return this;
  }

  public AgentBuilder outputSchema(String jqExpression) {
    this.outputSchema = jqExpression;
    return this;
  }

  public AgentBuilder userMessage(String template) {
    this.userMessageTemplate = template;
    return this;
  }

  public AgentBuilder model(ModelType modelType) {
    this.modelType = modelType;
    return this;
  }

  public AgentBuilder responseSchema(JsonSchema responseSchema) {
    this.responseSchema = responseSchema;
    return this;
  }

  public AgentBuilder responseSchema(Class<?> responseType) {
    JsonSchemaElement element;
    if (Collection.class.isAssignableFrom(responseType) || responseType.isArray()) {
      element = JsonArraySchema.builder().items(new JsonStringSchema()).build();
    } else {
      element = JsonSchemaElementUtils.jsonSchemaElementFrom(responseType);
    }
    if (!(element instanceof JsonObjectSchema)) {
      element = JsonObjectSchema.builder().addProperty("value", element).required("value").build();
    }
    this.responseSchema =
        JsonSchema.builder().name(responseType.getSimpleName()).rootElement(element).build();
    return this;
  }

  public AgentBuilder model(ChatModelProvider provider) {
    this.chatModelProvider = Objects.requireNonNull(provider, "provider must not be null");
    return this;
  }

  // package-private — for tests only
  AgentBuilder model(ChatModel model) {
    this.model = model;
    return this;
  }

  public Agent build() {
    if (systemPrompt == null) throw new IllegalStateException("systemPrompt is required");
    if (inputSchema == null) throw new IllegalStateException("inputSchema is required");
    if (outputSchema == null) throw new IllegalStateException("outputSchema is required");

    ChatModel resolvedModel = model;
    if (resolvedModel == null && chatModelProvider != null) {
      resolvedModel = chatModelProvider.get();
    }
    if (resolvedModel == null) {
      if (modelType == null) throw new IllegalStateException("model is required");
      resolvedModel =
          ServiceLoader.load(ChatModelProvider.class).stream()
              .map(ServiceLoader.Provider::get)
              .filter(p -> p.type() == modelType)
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("No ChatModelProvider found for: " + modelType))
              .get();
    }

    return new Agent(
        systemPrompt,
        userMessageTemplate,
        new JqTransformer(inputSchema),
        new JqTransformer(outputSchema),
        resolvedModel,
        responseSchema);
  }
}
