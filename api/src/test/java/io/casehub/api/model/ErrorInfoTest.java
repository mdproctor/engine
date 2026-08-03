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

import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorInfoTest {

  @Test
  void shouldCreateWithNoContext() {
    ErrorInfo error = ErrorInfo.of("TIMEOUT", "Operation timed out", false);

    assertThat(error.errorCode()).isEqualTo("TIMEOUT");
    assertThat(error.message()).isEqualTo("Operation timed out");
    assertThat(error.context()).isEmpty();
    assertThat(error.recoverable()).isFalse();
  }

  @Test
  void shouldCreateWithContext() {
    Map<String, Object> context = Map.of("threshold", 5000, "actual", 8000);
    ErrorInfo error = ErrorInfo.of("TIMEOUT", "Operation timed out", context, true);

    assertThat(error.errorCode()).isEqualTo("TIMEOUT");
    assertThat(error.message()).isEqualTo("Operation timed out");
    assertThat(error.context()).containsEntry("threshold", 5000).containsEntry("actual", 8000);
    assertThat(error.recoverable()).isTrue();
  }

  @Test
  void shouldHandleNullContext() {
    ErrorInfo error = ErrorInfo.of("VALIDATION_FAILED", "Invalid input", null, false);

    assertThat(error.errorCode()).isEqualTo("VALIDATION_FAILED");
    assertThat(error.message()).isEqualTo("Invalid input");
    assertThat(error.context()).isEmpty();
    assertThat(error.recoverable()).isFalse();
  }

  @Test
  void shouldCreateImmutableContextCopy() {
    Map<String, Object> mutableContext = new java.util.HashMap<>();
    mutableContext.put("field", "value");
    ErrorInfo error = ErrorInfo.of("ERROR", "Test", mutableContext, true);

    // Mutating original should not affect ErrorInfo
    mutableContext.put("field", "changed");

    assertThat(error.context()).containsEntry("field", "value");
  }
}
