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

import io.casehub.engine.common.internal.config.ConfigContext;
import io.casehub.engine.common.internal.config.ConfigManager;
import io.casehub.engine.common.internal.config.SecretManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Integration test for ConfigContext infrastructure.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>ConfigContext CDI wiring (ConfigManager + SecretManager)
 *   <li>ConfigManager resolution from Quarkus MicroProfile Config
 *   <li>SecretManager resolution with nested property support
 * </ul>
 */
@QuarkusTest
@TestProfile(ConfigContextIntegrationTest.Profile.class)
class ConfigContextIntegrationTest {

  @Inject ConfigContext configContext;

  @Test
  void shouldWireConfigContext() {
    assertThat(configContext).isNotNull();
    assertThat(configContext.configManager()).isNotNull();
    assertThat(configContext.secretManager()).isNotNull();
  }

  @Test
  void shouldResolveConfigFromQuarkus() {
    ConfigManager cm = configContext.configManager();

    Optional<String> strValue = cm.config("configcontext.test.string", String.class);
    assertThat(strValue).isPresent().hasValue("integration-test");

    Optional<Integer> intValue = cm.config("configcontext.test.number", Integer.class);
    assertThat(intValue).isPresent().hasValue(100);

    Optional<Boolean> boolValue = cm.config("configcontext.test.enabled", Boolean.class);
    assertThat(boolValue).isPresent().hasValue(true);

    Collection<String> multiValue = cm.multiConfig("configcontext.test.tags", String.class);
    assertThat(multiValue).containsExactly("config", "secret", "integration");
  }

  @Test
  void shouldResolveSecretWithNesting() {
    SecretManager sm = configContext.secretManager();

    Map<String, Object> secret = sm.secret("testapi");

    // Simple properties
    assertThat(secret).containsEntry("apiKey", "test-api-key-123");
    assertThat(secret).containsEntry("endpoint", "https://test.api.example.com");

    // Nested properties
    assertThat(secret).containsKey("auth");
    @SuppressWarnings("unchecked")
    Map<String, Object> auth = (Map<String, Object>) secret.get("auth");
    assertThat(auth).containsEntry("type", "bearer");
    assertThat(auth).containsEntry("timeout", "30");
  }

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          // ConfigManager test properties
          "configcontext.test.string", "integration-test",
          "configcontext.test.number", "100",
          "configcontext.test.enabled", "true",
          "configcontext.test.tags", "config,secret,integration",
          // SecretManager test properties
          "testapi.apiKey", "test-api-key-123",
          "testapi.endpoint", "https://test.api.example.com",
          "testapi.auth.type", "bearer",
          "testapi.auth.timeout", "30");
    }
  }
}
