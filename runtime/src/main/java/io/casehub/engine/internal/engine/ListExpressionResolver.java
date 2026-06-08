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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.evaluator.ListEvaluator;
import io.casehub.api.model.evaluator.ListEvaluator.JQList;
import io.casehub.api.model.evaluator.ListEvaluator.StaticList;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Resolves {@link ListEvaluator} specs to concrete {@code Set<String>} values.
 *
 * <p>Extracted from {@code CaseContextChangedEventHandler} so the six evaluation branches can be
 * unit-tested in isolation via {@link #resolveJq(JsonNode, JQList, String)}.
 */
@ApplicationScoped
public class ListExpressionResolver {

  private static final Logger LOG = Logger.getLogger(ListExpressionResolver.class);

  /**
   * Sentinel returned when JQ resolution fails. Checked with {@link #isFailed(Set)} (== identity,
   * not .equals). The {@code unmodifiableSet} wrapper prevents accidental mutation; only this
   * reference can compare equal via ==.
   */
  static final Set<String> RESOLUTION_FAILED = Collections.unmodifiableSet(new HashSet<>());

  /** Returns {@code true} iff {@code result} is the {@link #RESOLUTION_FAILED} sentinel. */
  public static boolean isFailed(Set<String> result) {
    return result == RESOLUTION_FAILED;
  }

  @Inject JQEvaluator jqEvaluator;

  /**
   * Resolves a {@link ListEvaluator} to a concrete set of strings.
   *
   * @param instance the current case instance — only accessed when {@code spec} is a {@link JQList}
   * @param spec the evaluator spec; null means "no restriction" → returns null
   * @param fieldName for log messages only
   * @return resolved set, null (no restriction), or {@link #RESOLUTION_FAILED}
   */
  public Set<String> resolve(CaseInstance instance, ListEvaluator spec, String fieldName) {
    if (spec == null) return null;
    return switch (spec) {
      case StaticList s -> s.values();
      case JQList jq -> resolveJq(instance.getCaseContext().asJsonNode(), jq, fieldName);
    };
  }

  /** Package-private to allow direct unit testing without constructing a {@link CaseInstance}. */
  Set<String> resolveJq(JsonNode context, JQList jq, String fieldName) {
    try {
      ValidationResult vr = jqEvaluator.eval(jq.expression(), context);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
        LOG.errorf(
            "'%s' JQ evaluation failed: expression '%s' — %s",
            fieldName, jq.expression(), vr.error());
        return RESOLUTION_FAILED;
      }
      JsonNode result = vr.output().get(0);
      if (!result.isArray()) {
        LOG.errorf(
            "'%s' JQ expression returned non-array: '%s' produced node type %s",
            fieldName, jq.expression(), result.getNodeType());
        return RESOLUTION_FAILED;
      }
      if (result.size() == 0) {
        LOG.warnf(
            "'%s' JQ expression returned empty array: '%s' — PlanItem stays PENDING",
            fieldName, jq.expression());
        return RESOLUTION_FAILED;
      }
      Set<String> groups = new LinkedHashSet<>();
      for (JsonNode element : result) {
        if (!element.isTextual()) {
          LOG.errorf(
              "'%s' JQ expression returned non-string element in array: node type %s",
              fieldName, element.getNodeType());
          return RESOLUTION_FAILED;
        }
        groups.add(element.asText());
      }
      return Collections.unmodifiableSet(groups);
    } catch (Exception e) {
      LOG.errorf(e, "'%s' JQ evaluation threw: expression '%s'", fieldName, jq.expression());
      return RESOLUTION_FAILED;
    }
  }
}
