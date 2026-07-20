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
package io.casehub.api.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class DataRefTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void of_creates_ref_with_class_name() {
    var ref = DataRef.of("doc-store", "doc-123", String.class);
    assertThat(ref.source()).isEqualTo("doc-store");
    assertThat(ref.key()).isEqualTo("doc-123");
    assertThat(ref.typeName()).isEqualTo("java.lang.String");
  }

  @Test
  void toJson_produces_discriminated_object() {
    var ref = DataRef.of("doc-store", "doc-123", String.class);
    JsonNode json = ref.toJson(MAPPER);
    assertThat(json.has("$dataRef")).isTrue();
    assertThat(json.get("$dataRef").get("source").asText()).isEqualTo("doc-store");
    assertThat(json.get("$dataRef").get("key").asText()).isEqualTo("doc-123");
    assertThat(json.get("$dataRef").get("type").asText()).isEqualTo("java.lang.String");
  }

  @Test
  void isRef_detects_discriminator() {
    var ref = DataRef.of("s", "k", String.class);
    assertThat(DataRef.isRef(ref.toJson(MAPPER))).isTrue();
    assertThat(DataRef.isRef(MAPPER.createObjectNode().put("foo", "bar"))).isFalse();
    assertThat(DataRef.isRef(null)).isFalse();
  }

  @Test
  void fromJson_round_trips() {
    var original = DataRef.of("doc-store", "doc-123", String.class);
    JsonNode json = original.toJson(MAPPER);
    DataRef<?> parsed = DataRef.fromJson(json);
    assertThat(parsed.source()).isEqualTo("doc-store");
    assertThat(parsed.key()).isEqualTo("doc-123");
    assertThat(parsed.typeName()).isEqualTo("java.lang.String");
  }

  @Test
  void fromJson_rejects_malformed() {
    ObjectNode bad = MAPPER.createObjectNode();
    bad.set("$dataRef", MAPPER.createObjectNode().put("source", "s"));
    assertThatThrownBy(() -> DataRef.fromJson(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source, key, and type");
  }

  @Test
  void constructor_rejects_null_fields() {
    assertThatThrownBy(() -> new DataRef<>(null, "k", "t"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DataRef<>("s", null, "t"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DataRef<>("s", "k", null))
        .isInstanceOf(NullPointerException.class);
  }
}
