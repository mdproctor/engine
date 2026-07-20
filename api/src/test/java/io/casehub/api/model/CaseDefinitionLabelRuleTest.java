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

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseDefinitionLabelRuleTest {

  @Test
  void labelRules_empty_by_default() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("test").version("1.0").build();
    assertThat(def.getLabelRules()).isEmpty();
  }

  @Test
  void labelRule_builder_single() {
    LabelRule rule =
        new LabelRule("r1", trueCondition(), List.of(new LabelAction.Add("priority/high")));
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .labelRule(rule)
            .build();
    assertThat(def.getLabelRules()).hasSize(1);
    assertThat(def.getLabelRules().get(0).name()).isEqualTo("r1");
  }

  @Test
  void labelRules_builder_list() {
    LabelRule r1 = new LabelRule("r1", trueCondition(), List.of(new LabelAction.Add("a")));
    LabelRule r2 = new LabelRule("r2", falseCondition(), List.of(new LabelAction.Remove("a")));
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .labelRules(List.of(r1, r2))
            .build();
    assertThat(def.getLabelRules()).hasSize(2);
  }

  @Test
  void labelRules_immutable_copy() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("test").version("1.0").build();
    assertThatThrownBy(() -> def.getLabelRules().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void labelRule_evaluate_integrates_with_platform() {
    LabelRule rule =
        new LabelRule(
            "high-priority",
            condition(ctx -> "HIGH".equals(ctx.get("severity"))),
            List.of(new LabelAction.Add("priority/high")));
    List<LabelAction> actions = LabelRule.evaluate(List.of(rule), Map.of("severity", "HIGH"));
    assertThat(actions).hasSize(1);
    assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("priority/high");
  }

  @Test
  void labelRule_evaluate_non_matching_returns_empty() {
    LabelRule rule =
        new LabelRule(
            "high-priority",
            condition(ctx -> "HIGH".equals(ctx.get("severity"))),
            List.of(new LabelAction.Add("priority/high")));
    List<LabelAction> actions = LabelRule.evaluate(List.of(rule), Map.of("severity", "LOW"));
    assertThat(actions).isEmpty();
  }

  private static CompiledExpression<Map<String, Object>, Boolean> trueCondition() {
    return new CompiledExpression<>() {
      @Override
      public String type() {
        return "test";
      }

      @Override
      public Boolean eval(Map<String, Object> ctx) {
        return Boolean.TRUE;
      }
    };
  }

  private static CompiledExpression<Map<String, Object>, Boolean> falseCondition() {
    return new CompiledExpression<>() {
      @Override
      public String type() {
        return "test";
      }

      @Override
      public Boolean eval(Map<String, Object> ctx) {
        return Boolean.FALSE;
      }
    };
  }

  private static CompiledExpression<Map<String, Object>, Boolean> condition(
      java.util.function.Function<Map<String, Object>, Boolean> fn) {
    return new CompiledExpression<>() {
      @Override
      public String type() {
        return "test";
      }

      @Override
      public Boolean eval(Map<String, Object> ctx) {
        return fn.apply(ctx);
      }
    };
  }
}
