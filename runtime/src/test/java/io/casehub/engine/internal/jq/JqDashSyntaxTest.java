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
 * Test demonstrating why config map names with dashes require quotes in JQ.
 *
 * <p>JQ treats dashes as subtraction operators, so:
 *
 * <ul>
 *   <li>$config.app-config -> interpreted as "$config.app minus config"
 *   <li>$config."app-config" -> correct: property named "app-config"
 * </ul>
 */
@QuarkusTest
@TestProfile(JqDashSyntaxTest.Profile.class)
class JqDashSyntaxTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject JQEvaluator jqEvaluator;

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "app-config.timeout", "5000",
          "appconfig.timeout", "3000");
    }
  }

  @Test
  void withoutQuotes_shouldFail() {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // Without quotes: JQ interprets dash as subtraction operator
    // $config.app-config becomes: $config.app minus config (where config is treated as function)
    ValidationResult result =
        jqEvaluator.eval(
            "$config.app-config.timeout", emptyContext, Set.of(), Set.of("app-config"));

    assertThat(result.ok()).isFalse();
    // JQ thinks "config" is a function, not a variable
    assertThat(result.error()).contains("Function config/0 does not exist");
  }

  @Test
  void withQuotes_shouldWork() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // With quotes: JQ treats as property name
    ValidationResult result =
        jqEvaluator.eval(
            "$config.\"app-config\".timeout", emptyContext, Set.of(), Set.of("app-config"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("5000");
  }

  @Test
  void noDash_noQuotesNeeded() throws Exception {
    JsonNode emptyContext = MAPPER.createObjectNode();

    // No dash: works without quotes
    ValidationResult result =
        jqEvaluator.eval("$config.appconfig.timeout", emptyContext, Set.of(), Set.of("appconfig"));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(1);
    assertThat(result.output().get(0).asText()).isEqualTo("3000");
  }
}
