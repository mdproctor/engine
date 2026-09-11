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
package io.casehub.engine.common.internal.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaseKeyTest {

  @Test
  void equalKeys_haveSameHashCode() {
    CaseKey k1 = new CaseKey("ns", "name", "1.0");
    CaseKey k2 = new CaseKey("ns", "name", "1.0");
    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
  }

  @Test
  void differentNamespace_notEqual() {
    assertThat(new CaseKey("ns1", "name", "1.0")).isNotEqualTo(new CaseKey("ns2", "name", "1.0"));
  }

  @Test
  void of_fromCaseMetaModel_extractsFields() {
    CaseMetaModel m = new CaseMetaModel();
    m.setNamespace("ns");
    m.setName("name");
    m.setVersion("2.0");
    m.id = 99L;
    m.tenancyId = "tenant-x";
    m.setDsl("some-dsl");

    CaseKey key = CaseKey.of(m);
    assertThat(key).isEqualTo(new CaseKey("ns", "name", "2.0"));
  }

  @Test
  void hashCode_isStable_afterCaseMetaModelMutation() {
    CaseMetaModel m = new CaseMetaModel();
    m.setNamespace("ns");
    m.setName("name");
    m.setVersion("1.0");
    CaseKey key = CaseKey.of(m);
    int hashBefore = key.hashCode();

    m.setNamespace("mutated");
    m.setName("also-mutated");

    assertThat(key.hashCode()).isEqualTo(hashBefore);
    assertThat(key.namespace()).isEqualTo("ns");
  }
}
