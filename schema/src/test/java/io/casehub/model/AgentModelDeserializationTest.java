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
package io.casehub.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AgentModelDeserializationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

  @Test
  void deserialize_agentWithOpenAI_parsesAllFields() throws IOException {
    String yaml =
        """
        name: sentiment-analyzer
        capabilities:
          - analyzeSentiment
        agent:
          systemPrompt: "Analyze sentiment"
          inputSchema: "{ text: .text }"
          outputSchema: "{ sentiment: .sentiment }"
          model:
            openai:
              apiKey: "sk-test-key-12345"
              modelName: "gpt-4"
              temperature: 0.7
        """;

    Worker worker = MAPPER.readValue(yaml, Worker.class);

    assertNotNull(worker);
    assertEquals("sentiment-analyzer", worker.getName());
    assertNotNull(worker.getAgent());

    Agent agent = worker.getAgent();
    assertEquals("Analyze sentiment", agent.getSystemPrompt());
    assertEquals("{ text: .text }", agent.getInputSchema());
    assertEquals("{ sentiment: .sentiment }", agent.getOutputSchema());

    assertNotNull(agent.getModel());
    assertNotNull(agent.getModel().getOpenai());

    OpenAiModel openai = agent.getModel().getOpenai();
    assertEquals("sk-test-key-12345", openai.getApiKey());
    assertEquals("gpt-4", openai.getModelName());
    assertEquals(0.7, openai.getTemperature());
  }

  @Test
  void deserialize_agentWithOllama_parsesAllFields() throws IOException {
    String yaml =
        """
        name: doc-classifier
        capabilities:
          - classify
        agent:
          systemPrompt: "Classify documents"
          inputSchema: "{ content: .document }"
          outputSchema: "{ category: .category }"
          model:
            ollama:
              baseUrl: "http://localhost:11434"
              modelName: "llama2"
              temperature: 0.5
              topK: 40
        """;

    Worker worker = MAPPER.readValue(yaml, Worker.class);

    assertNotNull(worker);
    assertNotNull(worker.getAgent());

    Agent agent = worker.getAgent();
    assertNotNull(agent.getModel().getOllama());

    OllamaModel ollama = agent.getModel().getOllama();
    assertEquals("http://localhost:11434", ollama.getBaseUrl());
    assertEquals("llama2", ollama.getModelName());
    assertEquals(0.5, ollama.getTemperature());
    assertEquals(40, ollama.getTopK());
  }

  @Test
  void deserialize_agentWithAllProviders_deserializesCorrectly() throws IOException {
    InputStream is =
        getClass().getClassLoader().getResourceAsStream("examples/agent-worker-example.yaml");
    assertNotNull(is, "Example YAML file not found");

    CaseDefinition caseDefinition = MAPPER.readValue(is, CaseDefinition.class);

    assertNotNull(caseDefinition);
    assertNotNull(caseDefinition.getSpec());
    assertNotNull(caseDefinition.getSpec().getWorkers());
    assertEquals(1, caseDefinition.getSpec().getWorkers().size());

    Worker worker = caseDefinition.getSpec().getWorkers().get(0);
    assertEquals("sentiment-analyzer", worker.getName());
    assertNotNull(worker.getAgent());

    Agent agent = worker.getAgent();
    assertNotNull(agent.getSystemPrompt());
    assertNotNull(agent.getInputSchema());
    assertNotNull(agent.getOutputSchema());
    assertNotNull(agent.getModel());
    assertNotNull(agent.getModel().getOpenai());
  }

  @Test
  void deserialize_anthropicModel_parsesAllFields() throws IOException {
    String yaml =
        """
        name: assistant
        capabilities:
          - assist
        agent:
          systemPrompt: "You are helpful"
          inputSchema: "."
          outputSchema: "."
          model:
            anthropic:
              apiKey: "sk-ant-test123"
              modelName: "claude-3-sonnet-20240229"
              version: "2023-06-01"
              temperature: 0.8
              maxTokens: 1024
              topP: 0.9
              topK: 40
              baseUrl: "https://api.anthropic.com"
        """;

    Worker worker = MAPPER.readValue(yaml, Worker.class);
    AnthropicModel anthropic = worker.getAgent().getModel().getAnthropic();

    assertNotNull(anthropic);
    assertEquals("sk-ant-test123", anthropic.getApiKey());
    assertEquals("claude-3-sonnet-20240229", anthropic.getModelName());
    assertEquals("2023-06-01", anthropic.getVersion());
    assertEquals(0.8, anthropic.getTemperature());
    assertEquals(1024, anthropic.getMaxTokens());
    assertEquals(0.9, anthropic.getTopP());
    assertEquals(40, anthropic.getTopK());
    assertEquals("https://api.anthropic.com", anthropic.getBaseUrl());
  }

  @Test
  void deserialize_mistralAiModel_parsesAllFields() throws IOException {
    String yaml =
        """
        name: assistant
        capabilities:
          - assist
        agent:
          systemPrompt: "Helpful assistant"
          inputSchema: "."
          outputSchema: "."
          model:
            mistralAi:
              apiKey: "test-mistral-key"
              modelName: "mistral-large-latest"
              temperature: 0.6
              maxTokens: 2000
              topP: 0.95
        """;

    Worker worker = MAPPER.readValue(yaml, Worker.class);
    MistralAiModel mistral = worker.getAgent().getModel().getMistralAi();

    assertNotNull(mistral);
    assertEquals("test-mistral-key", mistral.getApiKey());
    assertEquals("mistral-large-latest", mistral.getModelName());
    assertEquals(0.6, mistral.getTemperature());
    assertEquals(2000, mistral.getMaxTokens());
    assertEquals(0.95, mistral.getTopP());
  }

  @Test
  void deserialize_googleAiGeminiModel_parsesAllFields() throws IOException {
    String yaml =
        """
        name: assistant
        capabilities:
          - assist
        agent:
          systemPrompt: "Google assistant"
          inputSchema: "."
          outputSchema: "."
          model:
            googleAiGemini:
              apiKey: "test-google-key"
              modelName: "gemini-pro"
              temperature: 0.7
              maxTokens: 1500
              topP: 0.8
              topK: 50
        """;

    Worker worker = MAPPER.readValue(yaml, Worker.class);
    GoogleAiGeminiModel gemini = worker.getAgent().getModel().getGoogleAiGemini();

    assertNotNull(gemini);
    assertEquals("test-google-key", gemini.getApiKey());
    assertEquals("gemini-pro", gemini.getModelName());
    assertEquals(0.7, gemini.getTemperature());
    assertEquals(1500, gemini.getMaxTokens());
    assertEquals(0.8, gemini.getTopP());
    assertEquals(50, gemini.getTopK());
  }
}
