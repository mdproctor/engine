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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Integration test for YAML placeholder resolution during deserialization.
 *
 * <p>Verifies that {@code ${$secret.*}} and {@code ${$config.*}} placeholders are resolved once
 * during YAML deserialization via {@link ConfigSecretResolvingDeserializer}.
 */
@QuarkusTest
@TestProfile(YamlPlaceholderResolutionTest.Profile.class)
class YamlPlaceholderResolutionTest {

  @Inject @io.casehub.api.marshaller.YamlMapper ObjectMapper objectMapper;

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "yaml.placeholder.test.timeout", "60",
          "yaml.placeholder.secret.apiKey", "sk-integration-test",
          "yaml.placeholder.secret.orgId", "org-integration");
    }
  }

  @Test
  void shouldProvideSingletonObjectMapper() {
    assertThat(objectMapper).isNotNull();
    assertThat(objectMapper.getFactory().getFormatName()).isEqualTo("YAML");
  }

  @Test
  void shouldBeUsableForYamlParsing() throws Exception {
    String yaml = "key: value\nnumber: 42";

    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> parsed = objectMapper.readValue(yaml, java.util.Map.class);

    assertThat(parsed).containsEntry("key", "value").containsEntry("number", 42);
  }

  @Test
  void shouldResolveSecretPlaceholdersAtDeserialization() throws Exception {
    String yaml = "apiKey: \"${$secret.yaml.placeholder.secret.apiKey}\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.apiKey).isEqualTo("sk-integration-test");
  }

  @Test
  void shouldResolveConfigPlaceholdersAtDeserialization() throws Exception {
    String yaml = "timeout: \"${$config.yaml.placeholder.test.timeout}\"";

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.timeout).isEqualTo("60");
  }

  @Test
  void shouldResolveMultiplePlaceholdersInOneDocument() throws Exception {
    String yaml =
        """
        apiKey: "${$secret.yaml.placeholder.secret.apiKey}"
        orgId: "${$secret.yaml.placeholder.secret.orgId}"
        timeout: "${$config.yaml.placeholder.test.timeout}"
        """;

    TestModel result = objectMapper.readValue(yaml, TestModel.class);

    assertThat(result.apiKey).isEqualTo("sk-integration-test");
    assertThat(result.orgId).isEqualTo("org-integration");
    assertThat(result.timeout).isEqualTo("60");
  }

  public static class TestModel {
    public String apiKey;
    public String orgId;
    public String timeout;
  }
}
