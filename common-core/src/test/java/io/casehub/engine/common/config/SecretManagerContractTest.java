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
package io.casehub.engine.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.engine.common.internal.config.SecretManager;
import io.casehub.engine.common.internal.config.SecretNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Abstract contract test for SecretManager implementations.
 *
 * <p>Concrete implementations must extend this class and provide a SecretManager instance via
 * {@link #secretManager()}.
 */
public abstract class SecretManagerContractTest {

  /**
   * Provide the SecretManager implementation under test.
   *
   * <p>Implementations should ensure the following properties are available:
   *
   * <ul>
   *   <li>testservice.apiKey=test-key
   *   <li>testservice.endpoint=https://api.example.com
   *   <li>testservice.config.timeout=30
   *   <li>testservice.config.retries=3
   * </ul>
   */
  protected abstract SecretManager secretManager();

  @Test
  void shouldResolveSimpleSecret() {
    Map<String, Object> secret = secretManager().secret("testservice");

    assertThat(secret).containsEntry("apiKey", "test-key");
    assertThat(secret).containsEntry("endpoint", "https://api.example.com");
  }

  @Test
  void shouldBuildNestedMap() {
    Map<String, Object> secret = secretManager().secret("testservice");

    assertThat(secret).containsKey("config");
    @SuppressWarnings("unchecked")
    Map<String, Object> config = (Map<String, Object>) secret.get("config");

    assertThat(config).containsEntry("timeout", "30");
    assertThat(config).containsEntry("retries", "3");
  }

  @Test
  void shouldThrowSecretNotFoundExceptionForMissingSecret() {
    assertThatThrownBy(() -> secretManager().secret("nonexistent"))
        .isInstanceOf(SecretNotFoundException.class);
  }

  @Test
  void shouldThrowSecretNotFoundExceptionForEmptySecret() {
    // No properties with "empty." prefix exist
    assertThatThrownBy(() -> secretManager().secret("empty"))
        .isInstanceOf(SecretNotFoundException.class);
  }
}
