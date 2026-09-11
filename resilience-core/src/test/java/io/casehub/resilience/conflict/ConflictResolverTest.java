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
package io.casehub.resilience.conflict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConflictResolverTest {

  @Test
  void last_writer_wins_returns_incoming() {
    assertThat(ConflictResolver.lastWriterWins().resolve("k", "old", "new")).isEqualTo("new");
  }

  @Test
  void first_writer_wins_returns_existing() {
    assertThat(ConflictResolver.firstWriterWins().resolve("k", "old", "new")).isEqualTo("old");
  }

  @Test
  void first_writer_wins_null_existing_returns_incoming() {
    assertThat(ConflictResolver.firstWriterWins().resolve("k", null, "new")).isEqualTo("new");
  }

  @Test
  void fail_throws_when_existing_present() {
    assertThatThrownBy(() -> ConflictResolver.fail().resolve("k", "old", "new"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("k");
  }

  @Test
  void fail_returns_incoming_when_no_existing() {
    assertThat(ConflictResolver.fail().resolve("k", null, "new")).isEqualTo("new");
  }

  @Test
  void last_writer_wins_bean_returns_incoming() {
    assertThat(new LastWriterWinsConflictResolver().resolve("k", "old", "new")).isEqualTo("new");
  }
}
