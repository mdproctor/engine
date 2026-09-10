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
package io.casehub.engine.internal.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandContentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void serializes_with_deadline_when_present() throws Exception {
    final CommandContent content =
        new CommandContent(
            "COMMAND", "analyse", "42", Map.of("docId", "d1"), "2026-12-31T00:00:00Z");

    final JsonNode json = MAPPER.valueToTree(content);

    assertThat(json.get("type").asText()).isEqualTo("COMMAND");
    assertThat(json.get("capability").asText()).isEqualTo("analyse");
    assertThat(json.get("correlationId").asText()).isEqualTo("42");
    assertThat(json.get("deadline").asText()).isEqualTo("2026-12-31T00:00:00Z");
  }

  @Test
  void omits_deadline_from_json_when_null() throws Exception {
    final CommandContent content =
        new CommandContent("COMMAND", "analyse", "42", Map.of("docId", "d1"), null);

    final JsonNode json = MAPPER.valueToTree(content);

    assertThat(json.has("deadline")).as("deadline must be absent from JSON when null").isFalse();
  }
}
