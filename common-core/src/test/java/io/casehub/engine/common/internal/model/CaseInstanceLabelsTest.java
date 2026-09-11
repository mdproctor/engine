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

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseInstanceLabelsTest {

  @Test
  void labels_empty_by_default() {
    CaseInstance ci = new CaseInstance();
    assertThat(ci.getLabels()).isEmpty();
  }

  @Test
  void labels_mutable() {
    CaseInstance ci = new CaseInstance();
    ci.getLabels().add("priority/high");
    assertThat(ci.getLabels()).containsExactly("priority/high");
  }

  @Test
  void setLabels_replaces() {
    CaseInstance ci = new CaseInstance();
    ci.getLabels().add("old");
    ci.setLabels(new LinkedHashSet<>(Set.of("new/a", "new/b")));
    assertThat(ci.getLabels()).containsExactlyInAnyOrder("new/a", "new/b");
  }
}
