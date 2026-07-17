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
package io.casehub.api.model.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

class DeprecatedFieldRenamerTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private JsonNode parse(String yaml) throws Exception {
    return YAML.readTree(yaml);
  }

  @Test
  void apply_capabilityInputSchema_renamedToInputProjection() throws Exception {
    JsonNode node =
        parse(
            """
        spec:
          capabilities:
            - name: analyze
              inputSchema: "{ text: .text }"
        """);

    int count = DeprecatedFieldRenamer.apply(node);

    assertThat(count).isEqualTo(1);
    JsonNode cap = node.path("spec").path("capabilities").get(0);
    assertThat(cap.has("inputSchema")).isFalse();
    assertThat(cap.path("inputProjection").asText()).isEqualTo("{ text: .text }");
  }

  @Test
  void apply_capabilityOutputSchema_renamedToOutputProjection() throws Exception {
    JsonNode node =
        parse(
            """
        spec:
          capabilities:
            - name: analyze
              outputSchema: "{ result: .result }"
        """);

    int count = DeprecatedFieldRenamer.apply(node);

    assertThat(count).isEqualTo(1);
    JsonNode cap = node.path("spec").path("capabilities").get(0);
    assertThat(cap.has("outputSchema")).isFalse();
    assertThat(cap.path("outputProjection").asText()).isEqualTo("{ result: .result }");
  }

  @Test
  void apply_agentInputSchema_renamedToInputProjection() throws Exception {
    JsonNode node =
        parse(
            """
        spec:
          workers:
            - name: w1
              agent:
                inputSchema: "{ q: .query }"
                outputSchema: "{ a: .answer }"
        """);

    int count = DeprecatedFieldRenamer.apply(node);

    assertThat(count).isEqualTo(2);
    JsonNode agent = node.path("spec").path("workers").get(0).path("agent");
    assertThat(agent.has("inputSchema")).isFalse();
    assertThat(agent.has("outputSchema")).isFalse();
    assertThat(agent.path("inputProjection").asText()).isEqualTo("{ q: .query }");
    assertThat(agent.path("outputProjection").asText()).isEqualTo("{ a: .answer }");
  }

  @Test
  void apply_bothOldAndNewPresent_newWinsAndOldRemoved() throws Exception {
    JsonNode node =
        parse(
            """
        spec:
          capabilities:
            - name: analyze
              inputSchema: "old"
              inputProjection: "new"
        """);

    int count = DeprecatedFieldRenamer.apply(node);

    assertThat(count).isEqualTo(1);
    JsonNode cap = node.path("spec").path("capabilities").get(0);
    assertThat(cap.has("inputSchema")).isFalse();
    assertThat(cap.path("inputProjection").asText()).isEqualTo("new");
  }

  @Test
  void apply_newNamesAlready_noChanges() throws Exception {
    JsonNode node =
        parse(
            """
        spec:
          capabilities:
            - name: analyze
              inputProjection: "{ text: .text }"
              outputProjection: "{ result: .result }"
        """);

    int count = DeprecatedFieldRenamer.apply(node);

    assertThat(count).isEqualTo(0);
  }

  @Test
  void apply_noSpec_noError() throws Exception {
    JsonNode node =
        parse(
            """
        namespace: test
        name: Minimal
        version: 1.0.0
        """);

    int count = DeprecatedFieldRenamer.apply(node);

    assertThat(count).isEqualTo(0);
  }
}
