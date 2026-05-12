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
package io.casehub.engine.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.context.CaseContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MapCaseFile")
class MapCaseFileTest {

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("empty constructor creates empty map at version 0")
    void empty_createsEmpty() {
      final var map = new MapCaseFile();
      assertThat(map.isEmpty()).isTrue();
      assertThat(map.size()).isZero();
      assertThat(map.getVersion()).isZero();
    }

    @Test
    @DisplayName("map constructor pre-populates data")
    void mapConstructor_prePopulates() {
      final var map = new MapCaseFile(Map.of("a", "hello", "b", 42));
      assertThat(map.getString("a")).isEqualTo("hello");
      assertThat(map.getInt("b")).isEqualTo(42);
    }

    @Test
    @DisplayName("is a CaseContext")
    void isCaseContext() {
      assertThat(new MapCaseFile()).isInstanceOf(CaseContext.class);
    }
  }

  @Nested
  @DisplayName("put / get")
  class PutGet {

    @Test
    @DisplayName("put then get returns typed value")
    void put_get_roundtrip() {
      final var map = new MapCaseFile();
      map.put("score", 99);
      assertThat(map.get("score", Integer.class)).isEqualTo(99);
    }

    @Test
    @DisplayName("get returns null for missing key")
    void get_missingKey_returnsNull() {
      final var map = new MapCaseFile();
      assertThat(map.get("missing", String.class)).isNull();
    }

    @Test
    @DisplayName("put overwrites existing value")
    void put_overwrites() {
      final var map = new MapCaseFile();
      map.put("x", "first");
      map.put("x", "second");
      assertThat(map.get("x", String.class)).isEqualTo("second");
    }

    @Test
    @DisplayName("put null on absent key does not add the key — value absence is idempotent")
    void put_nullOnAbsentKey_doesNotAddKey() {
      // set() only writes when !Objects.equals(prev, value).
      // When both prev and value are null, Objects.equals returns true, so
      // !true == false — no write occurs and the key is never inserted.
      // Migration note: poc's HibernateCaseFile stored null entries directly.
      final var map = new MapCaseFile();
      map.put("k", null);
      assertThat(map.contains("k")).isFalse();
      assertThat(map.get("k", String.class)).isNull();
    }
  }

  @Nested
  @DisplayName("keys")
  class Keys {

    @Test
    @DisplayName("keys() reflects all put keys")
    void keys_reflectsPuts() {
      final var map = new MapCaseFile();
      map.put("a", 1);
      map.put("b", 2);
      assertThat(map.keys()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("keys() is empty before any put")
    void keys_emptyInitially() {
      assertThat(new MapCaseFile().keys()).isEmpty();
    }
  }

  @Nested
  @DisplayName("contains")
  class Contains {

    @Test
    @DisplayName("contains returns true after put")
    void contains_afterPut() {
      final var map = new MapCaseFile();
      map.put("present", "value");
      assertThat(map.contains("present")).isTrue();
    }

    @Test
    @DisplayName("contains returns false for missing key")
    void contains_missingKey() {
      assertThat(new MapCaseFile().contains("absent")).isFalse();
    }
  }

  @Nested
  @DisplayName("inherited behaviour")
  class Inherited {

    @Test
    @DisplayName("putIfAbsent does not overwrite existing value")
    void putIfAbsent_doesNotOverwrite() {
      final var map = new MapCaseFile();
      map.put("k", "original");
      map.putIfAbsent("k", "replacement");
      assertThat(map.get("k", String.class)).isEqualTo("original");
    }

    @Test
    @DisplayName("snapshot is an independent copy and preserves MapCaseFile type")
    void snapshot_isIndependentAndPreservesType() {
      final var map = new MapCaseFile();
      map.put("k", "v1");
      final var snap = map.snapshot();
      map.put("k", "v2");
      assertThat(snap.getString("k")).isEqualTo("v1");
      assertThat(map.getString("k")).isEqualTo("v2");
      assertThat(snap).isInstanceOf(MapCaseFile.class);
    }

    @Test
    @DisplayName("get() delegates type coercion to getAs() via Jackson")
    void get_delegatesTypeCoercionToGetAs() {
      final var map = new MapCaseFile();
      map.put("num", "42");
      assertThat(map.get("num", Integer.class)).isEqualTo(42);
    }
  }
}
