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
package io.casehub.engine.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.context.ContextLayer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotCaseContextTest {

  @Test
  void working_layer_returns_snapshot_data() {
    var snapshot = Map.<String, Object>of("txn", "SAR-001", "amount", 50000);
    var ctx = new SnapshotCaseContext(snapshot);

    assertThat(ctx.layer(ContextLayer.WORKING).getData()).isEqualTo(snapshot);
    assertThat(ctx.layer(ContextLayer.WORKING).get("txn")).isEqualTo("SAR-001");
    assertThat(ctx.layer(ContextLayer.WORKING).get("amount")).isEqualTo(50000);
  }

  @Test
  void working_layer_asJsonNode_returns_json() {
    var ctx = new SnapshotCaseContext(Map.of("key", "value"));
    var node = ctx.layer(ContextLayer.WORKING).asJsonNode();

    assertThat(node.has("key")).isTrue();
    assertThat(node.get("key").asText()).isEqualTo("value");
  }

  @Test
  void non_working_layers_return_empty() {
    var ctx = new SnapshotCaseContext(Map.of("key", "value"));

    assertThat(ctx.layer(ContextLayer.SEMANTIC).getData()).isEmpty();
    assertThat(ctx.layer(ContextLayer.EPISODIC).getData()).isEmpty();
    assertThat(ctx.layer("unknown").getData()).isEmpty();
  }

  @Test
  void getData_returns_snapshot() {
    var data = Map.<String, Object>of("a", 1);
    var ctx = new SnapshotCaseContext(data);
    assertThat(ctx.getData()).isEqualTo(data);
  }

  @Test
  void get_delegates_to_snapshot() {
    var ctx = new SnapshotCaseContext(Map.of("k", "v"));
    assertThat(ctx.get("k")).isEqualTo("v");
    assertThat(ctx.get("missing")).isNull();
  }

  @Test
  void typed_getters() {
    var ctx = new SnapshotCaseContext(Map.of("s", "hello", "i", 42, "b", true, "d", 3.14));
    assertThat(ctx.getString("s")).isEqualTo("hello");
    assertThat(ctx.getInt("i")).isEqualTo(42);
    assertThat(ctx.getBoolean("b")).isTrue();
    assertThat(ctx.getDouble("d")).isEqualTo(3.14);
    assertThat(ctx.getString("missing")).isNull();
    assertThat(ctx.getInt("missing")).isNull();
  }

  @Test
  void mutation_methods_throw() {
    var ctx = new SnapshotCaseContext(Map.of());
    assertThatThrownBy(() -> ctx.set("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.remove("k")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.setAll(Map.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.setPath("p", "v"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.applyAndDiff("p", "v"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.applyDiff(null)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.merge(ctx)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.update("k", v -> v))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.computeIfAbsent("k", k -> k))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.putIfAbsent("k", "v"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.compareAndSet("k", "a", "b"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void contains_and_size() {
    var ctx = new SnapshotCaseContext(Map.of("a", 1, "b", 2));
    assertThat(ctx.contains("a")).isTrue();
    assertThat(ctx.contains("c")).isFalse();
    assertThat(ctx.size()).isEqualTo(2);
    assertThat(ctx.isEmpty()).isFalse();
    assertThat(ctx.getKeys()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void empty_snapshot() {
    var ctx = new SnapshotCaseContext(Map.of());
    assertThat(ctx.isEmpty()).isTrue();
    assertThat(ctx.size()).isZero();
  }

  @Test
  void asJsonNode_returns_full_snapshot() {
    var ctx = new SnapshotCaseContext(Map.of("x", 1));
    var node = ctx.asJsonNode();
    assertThat(node.get("x").asInt()).isEqualTo(1);
  }

  @Test
  void snapshot_returns_self() {
    var ctx = new SnapshotCaseContext(Map.of("k", "v"));
    assertThat(ctx.snapshot()).isSameAs(ctx);
  }

  @Test
  void version_is_zero() {
    var ctx = new SnapshotCaseContext(Map.of());
    assertThat(ctx.getVersion()).isZero();
  }
}
