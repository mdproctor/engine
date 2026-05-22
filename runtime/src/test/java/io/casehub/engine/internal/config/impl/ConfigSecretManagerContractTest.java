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

import io.casehub.engine.internal.config.SecretManagerContractTest;
import io.casehub.platform.api.expression.SecretManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;

@QuarkusTest
@TestProfile(ConfigSecretManagerContractTest.Profile.class)
class ConfigSecretManagerContractTest extends SecretManagerContractTest {

  @Inject SecretManager secretManager;

  @Override
  protected SecretManager secretManager() {
    return secretManager;
  }

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "testservice.apiKey", "test-key",
          "testservice.endpoint", "https://api.example.com",
          "testservice.config.timeout", "30",
          "testservice.config.retries", "3");
    }
  }
}
