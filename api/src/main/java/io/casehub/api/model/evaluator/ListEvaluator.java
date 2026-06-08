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
package io.casehub.api.model.evaluator;

import java.util.Set;

/**
 * Specification for a field that resolves to a list of strings at runtime.
 *
 * <p>Two implementations:
 *
 * <ul>
 *   <li>{@link StaticList} — static set of strings, evaluated immediately without case context
 *   <li>{@link JQList} — JQ expression evaluated against the case context at event-publish time
 * </ul>
 *
 * <p>Intentionally separate from {@link ExpressionEvaluator}, which is the input type for {@code
 * ExpressionEngine.evaluate(...): boolean}. {@code ListEvaluator} produces {@code Set<String>}, not
 * a boolean — placing it in the {@code ExpressionEvaluator} hierarchy would be type pollution.
 */
public sealed interface ListEvaluator permits ListEvaluator.StaticList, ListEvaluator.JQList {

  /** A literal, pre-defined set of strings — no runtime evaluation. */
  record StaticList(Set<String> values) implements ListEvaluator {
    public StaticList {
      values = values == null ? null : Set.copyOf(values);
    }
  }

  /** A JQ expression that resolves to a JSON array of strings at runtime. */
  record JQList(String expression) implements ListEvaluator {}
}
