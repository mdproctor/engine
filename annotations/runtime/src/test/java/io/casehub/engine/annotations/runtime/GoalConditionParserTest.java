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
package io.casehub.engine.annotations.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalConditionParserTest {

  @Test
  void single_key() {
    assertThat(GoalConditionParser.parseEffectKeys(".riskAssessment != null"))
        .containsExactly("riskAssessment");
  }

  @Test
  void compound_condition() {
    assertThat(
            GoalConditionParser.parseEffectKeys(".analysisResult != null and .clauseList != null"))
        .containsExactlyInAnyOrder("analysisResult", "clauseList");
  }

  @Test
  void boolean_check() {
    assertThat(GoalConditionParser.parseEffectKeys(".processed == true"))
        .containsExactly("processed");
  }

  @Test
  void nested_path_uses_root_key() {
    assertThat(GoalConditionParser.parseEffectKeys(".result.status != null"))
        .containsExactly("result");
  }

  @Test
  void empty_for_unparseable() {
    assertThat(GoalConditionParser.parseEffectKeys("some_function(.x)")).isEmpty();
  }

  @Test
  void empty_for_null_or_blank() {
    assertThat(GoalConditionParser.parseEffectKeys(null)).isEmpty();
    assertThat(GoalConditionParser.parseEffectKeys("")).isEmpty();
  }
}
