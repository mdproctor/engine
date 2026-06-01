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

import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseChannelTest {

  @Test
  void happyPath_allFieldsAccessible() {
    var ch =
        new CaseChannel(
            "ch-1",
            "auth-refactor",
            "team coordination",
            "qhorus",
            Map.of("endpoint", "https://qhorus.example.com"));
    assertThat(ch.id()).isEqualTo("ch-1");
    assertThat(ch.name()).isEqualTo("auth-refactor");
    assertThat(ch.purpose()).isEqualTo("team coordination");
    assertThat(ch.backendType()).isEqualTo("qhorus");
    assertThat(ch.properties()).containsEntry("endpoint", "https://qhorus.example.com");
  }

  @Test
  void nullProperties_defaultsToEmptyMap() {
    var ch = new CaseChannel("ch-2", "name", "purpose", "none", null);
    assertThat(ch.properties()).isEmpty();
  }

  @Test
  void properties_areImmutable() {
    var mutable = new java.util.HashMap<String, Object>(Map.of("k", "v"));
    var ch = new CaseChannel("ch-3", "name", "purpose", "none", mutable);
    mutable.put("extra", "injected");
    assertThat(ch.properties()).doesNotContainKey("extra");
  }

  @Test
  void blankId_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new CaseChannel("", "name", "purpose", "none", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  @Test
  void nullId_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new CaseChannel(null, "name", "purpose", "none", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  @Test
  void blankBackendType_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new CaseChannel("ch-1", "name", "purpose", "", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backendType");
  }

  @Test
  void equality_basedOnAllFields() {
    var a = new CaseChannel("ch-1", "n", "p", "qhorus", Map.of());
    var b = new CaseChannel("ch-1", "n", "p", "qhorus", Map.of());
    assertThat(a).isEqualTo(b);
  }

  @Test
  void propertiesWithNullValue_throwsIllegalArgumentException() {
    java.util.Map<String, Object> withNull = new java.util.HashMap<>();
    withNull.put("endpoint", null);
    assertThatThrownBy(() -> new CaseChannel("ch-1", "name", "purpose", "qhorus", withNull))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null values");
  }

  @Test
  void parseCaseId_standardFormat_withPurpose() {
    final var caseUuid = java.util.UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    assertThat(CaseChannel.parseCaseId("case-" + caseUuid + "/work")).isEqualTo(caseUuid);
  }

  @Test
  void parseCaseId_noPurposeSegment() {
    final var caseUuid = java.util.UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    assertThat(CaseChannel.parseCaseId("case-" + caseUuid)).isEqualTo(caseUuid);
  }

  @Test
  void parseCaseId_notCaseChannel_returnsNull() {
    assertThat(CaseChannel.parseCaseId("some-other-channel")).isNull();
  }

  @Test
  void parseCaseId_invalidUuid_returnsNull() {
    assertThat(CaseChannel.parseCaseId("case-not-a-uuid/work")).isNull();
  }

  @Test
  void parseCaseId_null_returnsNull() {
    assertThat(CaseChannel.parseCaseId(null)).isNull();
  }
}
