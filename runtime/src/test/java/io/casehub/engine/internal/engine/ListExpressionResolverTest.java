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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.evaluator.ListEvaluator.JQList;
import io.casehub.api.model.evaluator.ListEvaluator.StaticList;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ListExpressionResolver. Uses @QuarkusTest so JQEvaluator is discovered via CDI (it
 * is in casehub-engine-common, which is indexed in test application.properties). Tests call
 * resolveJq() directly (package-private) to avoid constructing CaseInstance.
 */
@QuarkusTest
class ListExpressionResolverTest {

  @Inject ListExpressionResolver resolver;

  private final ObjectMapper mapper = new ObjectMapper();

  // ── resolve() — top-level dispatch ──────────────────────────────────────────

  @Test
  void resolve_nullSpec_returnsNull() {
    assertThat(resolver.resolve(null, null, "candidateGroups")).isNull();
  }

  @Test
  void resolve_staticList_returnsValuesWithoutInvokingJQ() throws Exception {
    StaticList spec = new StaticList(Set.of("irb-committee", "ethics-board"));
    Set<String> result = resolver.resolve(null, spec, "candidateGroups");
    assertThat(result).containsExactlyInAnyOrder("irb-committee", "ethics-board");
  }

  // ── resolveJq() — JQ evaluation path ────────────────────────────────────────

  @Test
  void resolveJq_validStringArray_returnsStringSet() throws Exception {
    JsonNode context = mapper.readTree("{\"groups\": [\"ethics\", \"irb\"]}");
    Set<String> result = resolver.resolveJq(context, new JQList(".groups"), "candidateGroups");
    assertThat(result).containsExactlyInAnyOrder("ethics", "irb");
    assertThat(ListExpressionResolver.isFailed(result)).isFalse();
  }

  @Test
  void resolveJq_nonArray_returnsResolutionFailed() throws Exception {
    JsonNode context = mapper.readTree("{\"groups\": \"not-an-array\"}");
    Set<String> result = resolver.resolveJq(context, new JQList(".groups"), "candidateGroups");
    assertThat(ListExpressionResolver.isFailed(result)).isTrue();
  }

  @Test
  void resolveJq_emptyArray_returnsResolutionFailed() throws Exception {
    JsonNode context = mapper.readTree("{\"groups\": []}");
    Set<String> result = resolver.resolveJq(context, new JQList(".groups"), "candidateGroups");
    assertThat(ListExpressionResolver.isFailed(result)).isTrue();
  }

  @Test
  void resolveJq_absentField_returnsResolutionFailed() throws Exception {
    JsonNode context = mapper.readTree("{}");
    // .groups on {} produces null in JQ, which is not an array
    Set<String> result = resolver.resolveJq(context, new JQList(".groups"), "candidateGroups");
    assertThat(ListExpressionResolver.isFailed(result)).isTrue();
  }

  @Test
  void resolveJq_invalidJqExpression_returnsResolutionFailed() throws Exception {
    JsonNode context = mapper.readTree("{}");
    Set<String> result =
        resolver.resolveJq(context, new JQList("this is not valid jq !!!"), "candidateGroups");
    assertThat(ListExpressionResolver.isFailed(result)).isTrue();
  }

  @Test
  void resolveJq_arrayWithNonStringElement_returnsResolutionFailed() throws Exception {
    JsonNode context = mapper.readTree("{\"groups\": [\"valid\", 42]}");
    Set<String> result = resolver.resolveJq(context, new JQList(".groups"), "candidateGroups");
    assertThat(ListExpressionResolver.isFailed(result)).isTrue();
  }
}
