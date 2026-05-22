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
package io.casehub.engine.internal.config.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.platform.api.expression.SecretManager;
import io.casehub.platform.api.expression.SecretNotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ConfigSecretManagerTest.Profile.class)
class ConfigSecretManagerTest {

  @Inject SecretManager secretManager;

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "openai.apiKey", "sk-test",
          "openai.organizationId", "org-test",
          "db.credentials.username", "admin",
          "db.credentials.password", "secret",
          "db.connection.host", "localhost",
          "simple.key", "value");
    }
  }

  @Test
  void shouldBuildSecretFromPrefixedProperties() {
    Map<String, Object> secret = secretManager.secret("openai");

    assertThat(secret)
        .containsEntry("apiKey", "sk-test")
        .containsEntry("organizationId", "org-test");
  }

  @Test
  void shouldBuildNestedMaps() {
    Map<String, Object> secret = secretManager.secret("db");

    assertThat(secret).containsKeys("credentials", "connection");

    @SuppressWarnings("unchecked")
    Map<String, Object> credentials = (Map<String, Object>) secret.get("credentials");
    assertThat(credentials).containsEntry("username", "admin").containsEntry("password", "secret");

    @SuppressWarnings("unchecked")
    Map<String, Object> connection = (Map<String, Object>) secret.get("connection");
    assertThat(connection).containsEntry("host", "localhost");
  }

  @Test
  void shouldThrowWhenSecretNotFound() {
    assertThatThrownBy(() -> secretManager.secret("nonexistent"))
        .isInstanceOf(SecretNotFoundException.class)
        .hasMessageContaining("nonexistent");
  }

  @Test
  void shouldHandleSingleLevelProperties() {
    Map<String, Object> secret = secretManager.secret("simple");
    assertThat(secret).containsEntry("key", "value");
  }
}
