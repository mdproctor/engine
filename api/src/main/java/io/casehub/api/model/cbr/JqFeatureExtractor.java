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

import java.util.Map;
import java.util.Objects;

/**
 * JQ-based feature extractor. Maps feature names to JQ expressions that extract values from case
 * contexts. Used in YAML-defined CBR configurations.
 *
 * @param featureExpressions Map of feature name → JQ expression (e.g., "amount" →
 *     ".transaction.amount")
 */
public record JqFeatureExtractor(Map<String, String> featureExpressions)
    implements FeatureExtractor {

  public static final String TYPE = "jq";

  public JqFeatureExtractor {
    Objects.requireNonNull(featureExpressions, "featureExpressions must not be null");
    if (featureExpressions.isEmpty()) {
      throw new IllegalArgumentException("featureExpressions must not be empty");
    }
    featureExpressions = Map.copyOf(featureExpressions);
  }

  @Override
  public String type() {
    return TYPE;
  }
}
