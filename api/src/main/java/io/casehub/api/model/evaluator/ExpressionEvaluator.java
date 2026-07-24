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

public interface ExpressionEvaluator
    extends io.casehub.platform.api.expression.ExpressionEvaluator {

  /**
   * Returns the type identifier for this evaluator, used by {@link
   * io.casehub.api.engine.ExpressionEngine} implementations to declare which evaluator type they
   * handle.
   */
  @Override
  String type();
}
