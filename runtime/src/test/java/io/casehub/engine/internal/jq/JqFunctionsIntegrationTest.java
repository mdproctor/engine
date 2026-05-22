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
package io.casehub.engine.internal.jq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.platform.expression.JQEvaluator;
import io.casehub.platform.expression.ValidationResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for $secret and $config JQ scope variables.
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>$secret.{name}.{property} resolves secrets via SecretManager
 *   <li>$config.{name}.{property} resolves config maps via ConfigManager
 *   <li>Nested properties work: $secret.openai.apiKey
 *   <li>Missing secrets/configs throw exceptions
 * </ul>
 */
@QuarkusTest
@TestProfile(JqFunctionsIntegrationTest.Profile.class)
class JqFunctionsIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject JQEvaluator jqEvaluator;

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "openai.apiKey", "sk-test-key-12345",
          "openai.organizationId", "org-test-67890",
          "openai.model.name", "gpt-4o-mini",
          "app-config.timeout", "5000",
          "app-config.retries", "3",
          "app-config.logLevel", "INFO",
          "feature-flags.analyticsEnabled", "true",
          "feature-flags.debugMode", "false",
          "model-params.temperature", "0.7",
          "model-params.maxTokens", "4096");
    }
  }

  // ========== $secret Tests ==========

  @Test
  void secretVariable_shouldResolveTopLevelSecret() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval("$secret.openai", emptyContext, Set.of("openai"), Set.of());

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);

    JsonNode secretNode = result.output().get(0);
    assertThat(secretNode.get("apiKey").asText()).isEqualTo("sk-test-key-12345");
    assertThat(secretNode.get("organizationId").asText()).isEqualTo("org-test-67890");
  }

  @Test
  void secretVariable_shouldResolveNestedProperty() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval("$secret.openai.apiKey", emptyContext, Set.of("openai"), Set.of());

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("sk-test-key-12345");
  }

  @Test
  void secretVariable_shouldResolveDeepNestedProperty() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval("$secret.openai.model.name", emptyContext, Set.of("openai"), Set.of());

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("gpt-4o-mini");
  }

  @Test
  void secretVariable_shouldFailWhenSecretNotFound() {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval("$secret.nonexistent", emptyContext, Set.of("nonexistent"), Set.of());

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("Secret not found: nonexistent");
  }

  @Test
  void secretVariable_shouldWorkInComplexExpression() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // Combine secret with string concatenation
    ValidationResult result =
        jqEvaluator.eval(
            "\"API Key: \" + $secret.openai.apiKey", emptyContext, Set.of("openai"), Set.of());

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("API Key: sk-test-key-12345");
  }

  // ========== $config Tests ==========

  @Test
  void configVariable_shouldResolveTopLevelConfig() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval("$config.\"app-config\"", emptyContext, Set.of(), Set.of("app-config"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);

    JsonNode configNode = result.output().get(0);
    assertThat(configNode.get("timeout").asText()).isEqualTo("5000");
    assertThat(configNode.get("retries").asText()).isEqualTo("3");
    assertThat(configNode.get("logLevel").asText()).isEqualTo("INFO");
  }

  @Test
  void configVariable_shouldResolveNestedProperty() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval(
            "$config.\"app-config\".timeout", emptyContext, Set.of(), Set.of("app-config"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("5000");
  }

  @Test
  void configVariable_shouldWorkWithFeatureFlags() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval(
            "$config.\"feature-flags\".analyticsEnabled",
            emptyContext,
            Set.of(),
            Set.of("feature-flags"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("true");
  }

  @Test
  void configVariable_shouldConvertToNumberWithPipe() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // Use JQ's tonumber filter to convert string to number
    ValidationResult result =
        jqEvaluator.eval(
            "$config.\"app-config\".timeout | tonumber",
            emptyContext,
            Set.of(),
            Set.of("app-config"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asInt()).isEqualTo(5000);
  }

  @Test
  void configVariable_shouldFailWhenConfigMapNotFound() {
    JsonNode emptyContext = MAPPER.createObjectNode();

    ValidationResult result =
        jqEvaluator.eval("$config.nonexistent", emptyContext, Set.of(), Set.of("nonexistent"));

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("ConfigMap not found: nonexistent");
  }

  @Test
  void configVariable_shouldWorkInBooleanExpression() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // Use config in conditional
    ValidationResult result =
        jqEvaluator.eval(
            "if $config.\"feature-flags\".analyticsEnabled == \"true\" then \"enabled\" else \"disabled\" end",
            emptyContext,
            Set.of(),
            Set.of("feature-flags"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("enabled");
  }

  // ========== Combined Usage Tests ==========

  @Test
  void shouldCombineSecretAndConfig() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // Build object combining secret and config
    ValidationResult result =
        jqEvaluator.eval(
            "{ apiKey: $secret.openai.apiKey, timeout: $config.\"app-config\".timeout }",
            emptyContext,
            Set.of("openai"),
            Set.of("app-config"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);

    JsonNode resultNode = result.output().get(0);
    assertThat(resultNode.get("apiKey").asText()).isEqualTo("sk-test-key-12345");
    assertThat(resultNode.get("timeout").asText()).isEqualTo("5000");
  }

  @Test
  void shouldUseConfigAndSecretWithContext() throws Exception {
    // Context with some data
    ObjectNode context = MAPPER.createObjectNode();
    context.put("userId", "user-123");
    context.put("action", "analyze");

    // Combine context, secret, and config
    ValidationResult result =
        jqEvaluator.eval(
            "{ user: .userId, action: .action, apiKey: $secret.openai.apiKey, retries: ($config.\"app-config\".retries | tonumber) }",
            context,
            Set.of("openai"),
            Set.of("app-config"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);

    JsonNode resultNode = result.output().get(0);
    assertThat(resultNode.get("user").asText()).isEqualTo("user-123");
    assertThat(resultNode.get("action").asText()).isEqualTo("analyze");
    assertThat(resultNode.get("apiKey").asText()).isEqualTo("sk-test-key-12345");
    assertThat(resultNode.get("retries").asInt()).isEqualTo(3);
  }

  @Test
  void shouldWorkInFilterExpression() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // Check if analytics is enabled
    ValidationResult result =
        jqEvaluator.eval(
            "$config.\"feature-flags\".analyticsEnabled == \"true\"",
            emptyContext,
            Set.of(),
            Set.of("feature-flags"));

    assertThat(result.ok()).isTrue();
    assertThat(result.isTrue()).isTrue();
  }
}
