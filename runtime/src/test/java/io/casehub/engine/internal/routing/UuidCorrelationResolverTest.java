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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidCorrelationResolverTest {

  private final UuidCorrelationResolver resolver = new UuidCorrelationResolver();

  @Test
  void id_is_uuid() {
    assertThat(resolver.id()).isEqualTo("uuid");
  }

  @Test
  void resolves_valid_uuid() {
    UUID expected = UUID.randomUUID();
    UUID result = resolver.resolve(expected.toString(), "tenant1").await().indefinitely();
    assertThat(result).isEqualTo(expected);
  }

  @Test
  void resolves_uuid_with_whitespace() {
    UUID expected = UUID.randomUUID();
    UUID result = resolver.resolve("  " + expected + "  ", "tenant1").await().indefinitely();
    assertThat(result).isEqualTo(expected);
  }

  @Test
  void rejects_invalid_uuid() {
    assertThatThrownBy(() -> resolver.resolve("not-a-uuid", "tenant1").await().indefinitely())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a valid UUID");
  }

  @Test
  void rejects_null_value() {
    assertThatThrownBy(() -> resolver.resolve(null, "tenant1").await().indefinitely())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be null");
  }
}
