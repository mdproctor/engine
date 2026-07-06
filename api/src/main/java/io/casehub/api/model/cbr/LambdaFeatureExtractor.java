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
package io.casehub.api.model.cbr;

import io.casehub.api.context.CaseContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Lambda-based feature extractor. Accepts a Java function that extracts features from a case
 * context. Used in Java DSL for programmatic CBR configurations.
 *
 * <p>Not serializable — use {@link JqFeatureExtractor} for YAML-defined cases.
 */
public final class LambdaFeatureExtractor implements FeatureExtractor {

  public static final String TYPE = "lambda";

  private final Function<CaseContext, Map<String, Object>> extractionFunction;

  public LambdaFeatureExtractor(
      final Function<CaseContext, Map<String, Object>> extractionFunction) {
    this.extractionFunction =
        Objects.requireNonNull(extractionFunction, "extractionFunction must not be null");
  }

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * Extracts features from the given case context.
   *
   * @param context the case context
   * @return map of feature name → value
   */
  public Map<String, Object> extract(final CaseContext context) {
    return extractionFunction.apply(context);
  }
}
