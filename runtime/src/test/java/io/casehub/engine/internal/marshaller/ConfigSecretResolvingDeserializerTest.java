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
package io.casehub.engine.internal.marshaller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.common.internal.config.SecretNotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ConfigSecretResolvingDeserializerTest.Profile.class)
class ConfigSecretResolvingDeserializerTest {

  @Inject @YamlMapper ObjectMapper objectMapper;

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          // Config properties
          "worker.timeout", "30",
          "app.retries", "3",
          // Secret properties (nested)
          "openai.apiKey", "sk-test-12345",
          "openai.organizationId", "org-test",
          "database.credentials.username", "admin",
          "database.credentials.password", "secret123");
    }
  }

  @Test
  void shouldResolveSecretPlaceholder() throws Exception {
    String yaml = "apiKey: \"${$secret.openai.apiKey}\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.apiKey).isEqualTo("sk-test-12345");
  }

  @Test
  void shouldResolveConfigPlaceholder() throws Exception {
    String yaml = "timeout: \"${$config.worker.timeout}\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.timeout).isEqualTo("30");
  }

  @Test
  void shouldResolveNestedSecretProperty() throws Exception {
    String yaml = "username: \"${$secret.database.credentials.username}\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.username).isEqualTo("admin");
  }

  @Test
  void shouldResolveMultiplePlaceholders() throws Exception {
    String yaml =
        """
        apiKey: "${$secret.openai.apiKey}"
        timeout: "${$config.worker.timeout}"
        username: "${$secret.database.credentials.username}"
        """;

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.apiKey).isEqualTo("sk-test-12345");
    assertThat(result.timeout).isEqualTo("30");
    assertThat(result.username).isEqualTo("admin");
  }

  @Test
  void shouldLeaveNonPlaceholderStringsUnchanged() throws Exception {
    String yaml = "apiKey: \"hardcoded-value\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.apiKey).isEqualTo("hardcoded-value");
  }

  @Test
  void shouldResolveMixedContent() throws Exception {
    String yaml =
        "message: \"API key is ${$secret.openai.apiKey} and timeout is ${$config.worker.timeout}\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.message).isEqualTo("API key is sk-test-12345 and timeout is 30");
  }

  @Test
  void shouldThrowWhenSecretNotFound() {
    String yaml = "apiKey: \"${$secret.nonexistent.key}\"";

    assertThatThrownBy(() -> objectMapper.readValue(yaml, TestModel.class))
        .hasCauseInstanceOf(SecretNotFoundException.class)
        .hasMessageContaining("nonexistent");
  }

  @Test
  void shouldThrowWhenConfigNotFound() {
    String yaml = "timeout: \"${$config.nonexistent.key}\"";

    assertThatThrownBy(() -> objectMapper.readValue(yaml, TestModel.class))
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Config property not found");
  }

  @Test
  void shouldThrowWhenSecretPropertyNotFound() {
    String yaml = "apiKey: \"${$secret.openai.nonexistentProperty}\"";

    assertThatThrownBy(() -> objectMapper.readValue(yaml, TestModel.class))
        .hasCauseInstanceOf(SecretNotFoundException.class)
        .hasMessageContaining("nonexistentProperty");
  }

  @Test
  void shouldHandleEmptyString() throws Exception {
    String yaml = "apiKey: \"\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.apiKey).isEmpty();
  }

  @Test
  void shouldHandleNullValue() throws Exception {
    String yaml = "apiKey: null";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.apiKey).isNull();
  }

  // Test model for deserialization
  public static class TestModel {
    public String apiKey;
    public String timeout;
    public String username;
    public String message;
  }
}
