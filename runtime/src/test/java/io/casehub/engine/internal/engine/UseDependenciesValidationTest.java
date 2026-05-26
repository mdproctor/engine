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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Use;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for use.secrets and use.configMaps validation.
 *
 * <p>Verifies fail-fast behavior: case definitions are rejected at load time if declared
 * secrets/configMaps do not exist.
 */
@QuarkusTest
@TestProfile(UseDependenciesValidationTest.Profile.class)
class UseDependenciesValidationTest {

  @Inject CaseDefinitionRegistry registry;

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "existing-secret.apiKey", "test-key",
          "existing-config.timeout", "5000");
    }
  }

  @Test
  void shouldRejectDefinitionWithMissingSecret() {
    CaseDefinition definition = new CaseDefinition("test", "test-case", "1.0");

    Use use = new Use();
    use.setSecrets(Set.of("nonexistent-secret"));
    definition.setUse(use);

    // Should fail with clear error message
    UniAssertSubscriber<Object> subscriber =
        registry
            .registerCaseDefinition(definition)
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create());

    subscriber.awaitFailure();
    assertThat(subscriber.getFailure())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Secret 'nonexistent-secret' declared in use.secrets not found");
  }

  @Test
  void shouldRejectDefinitionWithMissingConfigMap() {
    CaseDefinition definition = new CaseDefinition("test", "test-case", "1.0");

    Use use = new Use();
    use.setConfigMaps(Set.of("nonexistent-config"));
    definition.setUse(use);

    // Should fail with clear error message
    UniAssertSubscriber<Object> subscriber =
        registry
            .registerCaseDefinition(definition)
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create());

    subscriber.awaitFailure();
    assertThat(subscriber.getFailure())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ConfigMap 'nonexistent-config' declared in use.configMaps not found")
        .hasMessageContaining("ConfigMap not found: nonexistent-config");
  }

  @Test
  void shouldAcceptDefinitionWithValidDependencies() {
    CaseDefinition definition = new CaseDefinition("test", "test-case", "1.0");

    Use use = new Use();
    use.setSecrets(Set.of("existing-secret"));
    use.setConfigMaps(Set.of("existing-config"));
    definition.setUse(use);

    // Should succeed
    UniAssertSubscriber<Object> subscriber =
        registry
            .registerCaseDefinition(definition)
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create());

    subscriber.awaitItem();
    assertThat(subscriber.getItem()).isNotNull();
  }

  @Test
  void shouldAcceptDefinitionWithoutUseSection() {
    CaseDefinition definition = new CaseDefinition("test", "test-case", "1.0");
    // No use section - should be fine

    // Should succeed
    UniAssertSubscriber<Object> subscriber =
        registry
            .registerCaseDefinition(definition)
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create());

    subscriber.awaitItem();
    assertThat(subscriber.getItem()).isNotNull();
  }

  @Test
  void shouldAcceptDefinitionWithEmptyUseSection() {
    CaseDefinition definition = new CaseDefinition("test", "test-case", "1.0");

    Use use = new Use();
    // Empty sets - should be fine
    definition.setUse(use);

    // Should succeed
    UniAssertSubscriber<Object> subscriber =
        registry
            .registerCaseDefinition(definition)
            .subscribe()
            .withSubscriber(UniAssertSubscriber.create());

    subscriber.awaitItem();
    assertThat(subscriber.getItem()).isNotNull();
  }
}
