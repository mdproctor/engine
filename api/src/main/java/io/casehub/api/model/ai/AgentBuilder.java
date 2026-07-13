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
import java.util.function.UnaryOperator;

public final class AgentBuilder {

  private String systemPrompt;
  private String inputProjection;
  private String outputProjection;
  private UnaryOperator<JsonNode> inputTransformerFn;
  private UnaryOperator<JsonNode> outputTransformerFn;
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

  public AgentBuilder inputProjection(String jqExpression) {
    this.inputProjection = jqExpression;
    return this;
  }

  public AgentBuilder outputProjection(String jqExpression) {
    this.outputProjection = jqExpression;
    return this;
  }

  /** Supply a custom input transformer instead of a jq expression string. */
  public AgentBuilder inputTransformer(final UnaryOperator<JsonNode> fn) {
    this.inputTransformerFn = Objects.requireNonNull(fn, "inputTransformer must not be null");
    return this;
  }

  /** Supply a custom output transformer instead of a jq expression string. */
  public AgentBuilder outputTransformer(final UnaryOperator<JsonNode> fn) {
    this.outputTransformerFn = Objects.requireNonNull(fn, "outputTransformer must not be null");
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

  public AgentBuilder model(ChatModel model) {
    this.model = model;
    return this;
  }

  public Agent build() {
    if (systemPrompt == null) throw new IllegalStateException("systemPrompt is required");

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

    if (inputProjection != null && inputTransformerFn != null)
      throw new IllegalStateException(
          "Cannot set both inputSchema and inputTransformer — choose one");
    if (outputProjection != null && outputTransformerFn != null)
      throw new IllegalStateException(
          "Cannot set both outputSchema and outputTransformer — choose one");

    final UnaryOperator<JsonNode> resolvedInput =
        inputProjection != null
            ? new JqTransformer(inputProjection)::apply
            : (inputTransformerFn != null ? inputTransformerFn : UnaryOperator.identity());
    final UnaryOperator<JsonNode> resolvedOutput =
        outputProjection != null
            ? new JqTransformer(outputProjection)::apply
            : (outputTransformerFn != null ? outputTransformerFn : UnaryOperator.identity());

    return new Agent(
        systemPrompt,
        userMessageTemplate,
        resolvedInput,
        resolvedOutput,
        resolvedModel,
        responseSchema);
  }
}
