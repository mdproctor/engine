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

import static org.assertj.core.api.Assertions.*;

import io.casehub.api.context.CaseContext;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SubCaseMappingTest {

  @Test
  void expression_wrapsString() {
    SubCaseMapping mapping = SubCaseMapping.of("{ id: .caseId }");
    assertThat(mapping).isInstanceOf(SubCaseMapping.Expression.class);
    assertThat(((SubCaseMapping.Expression) mapping).expression()).isEqualTo("{ id: .caseId }");
  }

  @Test
  void lambda_wrapsFunction() {
    Function<CaseContext, Object> fn = ctx -> Map.of("x", "y");
    SubCaseMapping mapping = SubCaseMapping.of(fn);
    assertThat(mapping).isInstanceOf(SubCaseMapping.Lambda.class);
    assertThat(((SubCaseMapping.Lambda) mapping).fn()).isSameAs(fn);
  }

  @Test
  void expression_nullString_throws() {
    assertThatThrownBy(() -> SubCaseMapping.of((String) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void expression_blankString_throws() {
    assertThatThrownBy(() -> SubCaseMapping.of("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lambda_nullFunction_throws() {
    assertThatThrownBy(() -> SubCaseMapping.of((Function<CaseContext, Object>) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void sealedInterface_exhaustiveSwitch() {
    SubCaseMapping expr = SubCaseMapping.of(".field");
    String result =
        switch (expr) {
          case SubCaseMapping.Expression e -> "expr:" + e.expression();
          case SubCaseMapping.Lambda l -> "lambda";
        };
    assertThat(result).isEqualTo("expr:.field");
  }
}
