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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonPojoBridgeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  record TestPojo(String name, int value) {}

  @Test
  void initialiseDeserialisesFromNarrowedInput() {
    var bridge = new JacksonPojoBridge<>(TestPojo.class);
    var narrowed = MAPPER.valueToTree(Map.of("name", "alice", "value", 42));

    TestPojo result = bridge.initialise(null, narrowed);

    assertThat(result.name()).isEqualTo("alice");
    assertThat(result.value()).isEqualTo(42);
  }

  @Test
  void serialiseAndDeserialiseRoundTrip() {
    var bridge = new JacksonPojoBridge<>(TestPojo.class);
    var pojo = new TestPojo("bob", 7);

    var serialised = bridge.serialise(pojo);
    TestPojo deserialised = bridge.deserialise(serialised);

    assertThat(deserialised).isEqualTo(pojo);
  }

  @Test
  void contextTypeMatchesConstructorArg() {
    assertThat(new JacksonPojoBridge<>(TestPojo.class).contextType()).isEqualTo(TestPojo.class);
  }

  @Test
  void isNotLiveView() {
    assertThat(new JacksonPojoBridge<>(TestPojo.class).isLiveView()).isFalse();
  }

  @Test
  void extractOutputReturnsNull() {
    assertThat(new JacksonPojoBridge<>(TestPojo.class).extractOutput(new TestPojo("x", 1)))
        .isNull();
  }

  @Test
  void initialiseWithMissingFieldsProducesDefaults() {
    var bridge = new JacksonPojoBridge<>(TestPojo.class);
    var partial = MAPPER.valueToTree(Map.of("wrongField", "data"));

    TestPojo result = bridge.initialise(null, partial);
    assertThat(result.name()).isNull();
    assertThat(result.value()).isZero();
  }

  @Test
  void serialiseProducesValidJson() {
    var bridge = new JacksonPojoBridge<>(TestPojo.class);
    var node = bridge.serialise(new TestPojo("test", 99));
    assertThat(node.get("name").asText()).isEqualTo("test");
    assertThat(node.get("value").asInt()).isEqualTo(99);
  }

  record TimedPojo(String name, java.time.Instant createdAt) {}

  @Test
  void roundTripWithInstantField() {
    var bridge = new JacksonPojoBridge<>(TimedPojo.class);
    var pojo = new TimedPojo("test", java.time.Instant.parse("2026-01-01T00:00:00Z"));
    var json = bridge.serialise(pojo);
    TimedPojo result = bridge.deserialise(json);
    assertThat(result.createdAt()).isEqualTo(pojo.createdAt());
  }

  @Test
  void roundTripWithLocalDateField() {
    record DatedPojo(String name, java.time.LocalDate date) {}
    var bridge = new JacksonPojoBridge<>(DatedPojo.class);
    var pojo = new DatedPojo("test", java.time.LocalDate.of(2026, 8, 17));
    var json = bridge.serialise(pojo);
    DatedPojo result = bridge.deserialise(json);
    assertThat(result.date()).isEqualTo(pojo.date());
  }
}
