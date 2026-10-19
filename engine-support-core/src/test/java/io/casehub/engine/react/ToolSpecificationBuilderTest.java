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
package io.casehub.engine.react;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolSpecificationBuilderTest {

  @Test
  void extractsFieldNamesFromJqExpression() {
    var fields = ToolSpecificationBuilder.extractFieldNames(".query");
    assertThat(fields).containsExactly("query");
  }

  @Test
  void extractsMultipleFieldNames() {
    var fields = ToolSpecificationBuilder.extractFieldNames("{ query: .query, limit: .limit }");
    assertThat(fields).containsExactly("query", "limit");
  }

  @Test
  void identityJqReturnsEmptyFields() {
    var fields = ToolSpecificationBuilder.extractFieldNames(".");
    assertThat(fields).isEmpty();
  }

  @Test
  void nullInputReturnsEmptyFields() {
    var fields = ToolSpecificationBuilder.extractFieldNames(null);
    assertThat(fields).isEmpty();
  }
}
