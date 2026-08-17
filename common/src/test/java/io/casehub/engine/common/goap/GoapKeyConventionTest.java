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
package io.casehub.engine.common.goap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoapKeyConventionTest {

  @Test
  void simple_type() {
    assertThat(GoapKeyConvention.keyFor("AnalysisResult")).isEqualTo("analysisResult");
  }

  @Test
  void single_char() {
    assertThat(GoapKeyConvention.keyFor("X")).isEqualTo("x");
  }

  @Test
  void already_camelCase() {
    assertThat(GoapKeyConvention.keyFor("riskAssessment")).isEqualTo("riskAssessment");
  }

  @Test
  void parameterized_list() {
    assertThat(GoapKeyConvention.keyForParameterized("List", "Clause")).isEqualTo("clauseList");
  }

  @Test
  void parameterized_set() {
    assertThat(GoapKeyConvention.keyForParameterized("Set", "Tag")).isEqualTo("tagSet");
  }

  @Test
  void parameterized_map() {
    assertThat(GoapKeyConvention.keyForParameterized("Map", "String")).isEqualTo("stringMap");
  }

  @Test
  void no_collision() {
    Map<String, String> keys = Map.of("analysisResult", "analyse", "clauseList", "extractClauses");
    assertThat(GoapKeyConvention.detectCollision("riskAssessment", "assessRisk", keys)).isNull();
  }

  @Test
  void collision_detected() {
    var keys = new LinkedHashMap<String, String>();
    keys.put("stringList", "extractTags");
    String error = GoapKeyConvention.detectCollision("stringList", "extractErrors", keys);
    assertThat(error).isNotNull();
    assertThat(error).contains("extractTags");
    assertThat(error).contains("extractErrors");
  }
}
