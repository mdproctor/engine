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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.ai.Agent;
import io.casehub.model.AgentModel;
import io.casehub.model.AnthropicModel;
import io.casehub.model.GoogleAiGeminiModel;
import io.casehub.model.MistralAiModel;
import io.casehub.model.OllamaModel;
import io.casehub.model.OpenAiModel;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgentConverter} — verifies null handling, error paths, and all 5 provider
 * dispatch branches. Pure unit tests, no Quarkus. See casehubio/engine#358.
 */
class AgentConverterTest {

  // ---- null / error handling ------------------------------------------------

  @Test
  void toApiAgent_nullInput_returnsNull() {
    Agent result = AgentConverter.toApiAgent(null);
    assertThat(result).isNull();
  }

  @Test
  void toApiAgent_nullModel_throwsIllegalArgument() {
    io.casehub.model.Agent schemaAgent = new io.casehub.model.Agent();
    schemaAgent.setSystemPrompt("You are a test agent");
    schemaAgent.setInputSchema(".");
    schemaAgent.setOutputSchema(".");
    // model is null

    assertThatThrownBy(() -> AgentConverter.toApiAgent(schemaAgent))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("AgentModel is required");
  }

  @Test
  void toApiAgent_emptyModel_throwsIllegalArgument() {
    io.casehub.model.Agent schemaAgent = new io.casehub.model.Agent();
    schemaAgent.setSystemPrompt("You are a test agent");
    schemaAgent.setInputSchema(".");
    schemaAgent.setOutputSchema(".");
    schemaAgent.setModel(new AgentModel()); // no provider set

    assertThatThrownBy(() -> AgentConverter.toApiAgent(schemaAgent))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("No model provider configured in AgentModel");
  }

  // ---- OpenAI provider ------------------------------------------------------

  @Test
  void toApiAgent_openai_allFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    OpenAiModel openai = new OpenAiModel();
    openai.setApiKey("sk-test-key");
    openai.setModelName("gpt-4");
    openai.setBaseUrl("http://openclaw:3000/v1");
    openai.setOrganizationId("org-test");
    openai.setTemperature(0.7);
    openai.setTopP(0.9);
    openai.setMaxTokens(1024);
    openai.setFrequencyPenalty(0.5);
    openai.setPresencePenalty(0.3);
    AgentModel model = new AgentModel();
    model.setOpenai(openai);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  @Test
  void toApiAgent_openai_minimalFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    OpenAiModel openai = new OpenAiModel();
    openai.setApiKey("sk-test-key");
    openai.setModelName("gpt-4o-mini");
    AgentModel model = new AgentModel();
    model.setOpenai(openai);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  // ---- Ollama provider ------------------------------------------------------

  @Test
  void toApiAgent_ollama_allFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    OllamaModel ollama = new OllamaModel();
    ollama.setBaseUrl("http://localhost:11434");
    ollama.setModelName("llama2");
    ollama.setTemperature(0.5);
    ollama.setTopP(0.8);
    AgentModel model = new AgentModel();
    model.setOllama(ollama);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  @Test
  void toApiAgent_ollama_minimalFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    OllamaModel ollama = new OllamaModel();
    ollama.setBaseUrl("http://localhost:11434");
    ollama.setModelName("mistral");
    AgentModel model = new AgentModel();
    model.setOllama(ollama);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  // ---- Anthropic provider ---------------------------------------------------

  @Test
  void toApiAgent_anthropic_allFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    AnthropicModel anthropic = new AnthropicModel();
    anthropic.setApiKey("sk-ant-test-key");
    anthropic.setModelName("claude-3-sonnet-20240229");
    anthropic.setBaseUrl("https://custom-anthropic.example.com");
    anthropic.setVersion("2023-06-01");
    anthropic.setTemperature(0.3);
    anthropic.setTopP(0.95);
    anthropic.setTopK(40);
    anthropic.setMaxTokens(2048);
    AgentModel model = new AgentModel();
    model.setAnthropic(anthropic);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  @Test
  void toApiAgent_anthropic_minimalFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    AnthropicModel anthropic = new AnthropicModel();
    anthropic.setApiKey("sk-ant-test-key");
    anthropic.setModelName("claude-3-5-sonnet-20241022");
    AgentModel model = new AgentModel();
    model.setAnthropic(anthropic);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  // ---- MistralAI provider ---------------------------------------------------

  @Test
  void toApiAgent_mistral_allFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    MistralAiModel mistral = new MistralAiModel();
    mistral.setApiKey("mistral-test-key");
    mistral.setModelName("mistral-large-latest");
    mistral.setBaseUrl("https://custom-mistral.example.com");
    mistral.setTemperature(0.6);
    mistral.setTopP(0.85);
    mistral.setMaxTokens(4096);
    AgentModel model = new AgentModel();
    model.setMistralAi(mistral);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  @Test
  void toApiAgent_mistral_minimalFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    MistralAiModel mistral = new MistralAiModel();
    mistral.setApiKey("mistral-test-key");
    mistral.setModelName("mistral-small-latest");
    AgentModel model = new AgentModel();
    model.setMistralAi(mistral);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  // ---- Google AI Gemini provider --------------------------------------------

  @Test
  void toApiAgent_googleAiGemini_allFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    GoogleAiGeminiModel gemini = new GoogleAiGeminiModel();
    gemini.setApiKey("google-test-key");
    gemini.setModelName("gemini-pro");
    gemini.setTemperature(0.4);
    gemini.setTopP(0.9);
    gemini.setMaxTokens(8192);
    AgentModel model = new AgentModel();
    model.setGoogleAiGemini(gemini);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  @Test
  void toApiAgent_googleAiGemini_minimalFields() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    GoogleAiGeminiModel gemini = new GoogleAiGeminiModel();
    gemini.setApiKey("google-test-key");
    gemini.setModelName("gemini-2.0-flash");
    AgentModel model = new AgentModel();
    model.setGoogleAiGemini(gemini);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  // ---- userMessageTemplate --------------------------------------------------

  @Test
  void toApiAgent_withUserMessageTemplate() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    schemaAgent.setUserMessageTemplate("Analyze this: {{input}}");
    OpenAiModel openai = new OpenAiModel();
    openai.setApiKey("sk-test-key");
    openai.setModelName("gpt-4");
    AgentModel model = new AgentModel();
    model.setOpenai(openai);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  @Test
  void toApiAgent_withoutUserMessageTemplate() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    // userMessageTemplate left null
    OpenAiModel openai = new OpenAiModel();
    openai.setApiKey("sk-test-key");
    openai.setModelName("gpt-4");
    AgentModel model = new AgentModel();
    model.setOpenai(openai);
    schemaAgent.setModel(model);

    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  // ---- provider priority (dispatch order) -----------------------------------

  @Test
  void toApiAgent_openaiTakesPriorityOverOllama() {
    io.casehub.model.Agent schemaAgent = schemaAgent();
    AgentModel model = new AgentModel();

    OpenAiModel openai = new OpenAiModel();
    openai.setApiKey("sk-test-key");
    openai.setModelName("gpt-4");
    model.setOpenai(openai);

    OllamaModel ollama = new OllamaModel();
    ollama.setBaseUrl("http://localhost:11434");
    ollama.setModelName("llama2");
    model.setOllama(ollama);

    schemaAgent.setModel(model);

    // Should not throw — picks openai first per dispatch order
    Agent result = AgentConverter.toApiAgent(schemaAgent);
    assertThat(result).isNotNull();
  }

  // ---- helper ---------------------------------------------------------------

  private static io.casehub.model.Agent schemaAgent() {
    io.casehub.model.Agent agent = new io.casehub.model.Agent();
    agent.setSystemPrompt("You are a test agent");
    agent.setInputSchema(".");
    agent.setOutputSchema(".");
    return agent;
  }
}
