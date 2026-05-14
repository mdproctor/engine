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
package io.casehub.engine.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.internal.marshaller.YamlMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Full integration test for Config & Secrets Management System.
 *
 * <p>Verifies end-to-end integration:
 *
 * <ul>
 *   <li>ConfigContext infrastructure (ConfigManager + SecretManager)
 *   <li>YAML ObjectMapper with placeholder resolution
 *   <li>Config and secret resolution with nested properties
 * </ul>
 */
@QuarkusTest
@TestProfile(FullIntegrationTest.Profile.class)
class FullIntegrationTest {

  @Inject ConfigContext configContext;

  @Inject @YamlMapper ObjectMapper objectMapper;

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "full.test.config", "config-value",
          "full.test.secret.key", "secret-value",
          "nested.test.level1.level2.level3", "deep-value");
    }
  }

  @Test
  void shouldProvideCompleteInfrastructure() {
    // ConfigContext infrastructure
    assertThat(configContext).isNotNull();
    assertThat(configContext.configManager()).isNotNull();
    assertThat(configContext.secretManager()).isNotNull();

    // YAML ObjectMapper with placeholder resolution
    assertThat(objectMapper).isNotNull();
    assertThat(objectMapper.getFactory().getFormatName()).isEqualTo("YAML");
  }

  @Test
  void shouldResolveConfigAndSecrets() {
    // ConfigManager
    assertThat(configContext.configManager().config("full.test.config", String.class))
        .hasValue("config-value");

    // SecretManager
    Map<String, Object> secret = configContext.secretManager().secret("full.test.secret");
    assertThat(secret).containsEntry("key", "secret-value");
  }

  @Test
  void shouldHandleNestedSecrets() {
    Map<String, Object> secret = configContext.secretManager().secret("nested.test");

    @SuppressWarnings("unchecked")
    Map<String, Object> level1 = (Map<String, Object>) secret.get("level1");
    @SuppressWarnings("unchecked")
    Map<String, Object> level2 = (Map<String, Object>) level1.get("level2");

    assertThat(level2).containsEntry("level3", "deep-value");
  }
}
