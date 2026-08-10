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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChannelDeclarationTest {

  @Test
  void defaultTransportIsInMemory() {
    var decl = new ChannelDeclaration("ch", String.class, null, null);
    assertThat(decl.transport()).isEqualTo("in-memory");
  }

  @Test
  void defaultScopeIsCase() {
    var decl = new ChannelDeclaration("ch", String.class, null, null);
    assertThat(decl.scope()).isEqualTo(LifecycleScope.CASE);
  }

  @Test
  void bindingScopeRejected() {
    assertThatThrownBy(
            () -> new ChannelDeclaration("ch", String.class, null, LifecycleScope.BINDING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BINDING");
  }

  @Test
  void blankNameRejected() {
    assertThatThrownBy(() -> new ChannelDeclaration("  ", String.class, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullNameRejected() {
    assertThatThrownBy(() -> new ChannelDeclaration(null, String.class, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullRecordTypeRejected() {
    assertThatThrownBy(() -> new ChannelDeclaration("ch", null, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void compoundScopeAccepted() {
    var decl = new ChannelDeclaration("ch", String.class, "kafka", LifecycleScope.COMPOUND);
    assertThat(decl.scope()).isEqualTo(LifecycleScope.COMPOUND);
    assertThat(decl.transport()).isEqualTo("kafka");
  }

  @Test
  void equality() {
    var a = new ChannelDeclaration("ch", String.class, "in-memory", LifecycleScope.CASE);
    var b = new ChannelDeclaration("ch", String.class, "in-memory", LifecycleScope.CASE);
    assertThat(a).isEqualTo(b);
  }
}
