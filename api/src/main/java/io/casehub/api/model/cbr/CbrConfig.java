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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * CBR retrieval configuration. Specifies feature extraction, similarity scoring, and result
 * filtering for case-based reasoning.
 *
 * @param featureExtractor The feature extraction strategy (JQ or lambda)
 * @param topK Maximum number of similar cases to retrieve (must be >= 1)
 * @param minSimilarity Minimum similarity score threshold (0.0 to 1.0)
 * @param weights Feature weights for similarity computation (non-negative)
 * @param domain Optional domain filter for scoping retrieval
 * @param caseType Optional case type filter
 * @param vectorWeight Weight for semantic vector similarity (0.0 to 1.0)
 * @param timing Retrieval timing strategy — PER_EVALUATION (default) or CASE_LIFETIME
 */
public record CbrConfig(
    FeatureExtractor featureExtractor,
    int topK,
    double minSimilarity,
    Map<String, Double> weights,
    String domain,
    String caseType,
    double vectorWeight,
    CbrRetrievalTiming timing) {

  /** Controls when CBR retrieval occurs relative to the case lifecycle. */
  public enum CbrRetrievalTiming {
    /** Retrieve on every evaluation (default — current behaviour). */
    PER_EVALUATION,
    /** Retrieve once on first access and cache for the case's lifetime. */
    CASE_LIFETIME
  }

  public CbrConfig {
    Objects.requireNonNull(featureExtractor, "featureExtractor must not be null");
    if (topK < 1) {
      throw new IllegalArgumentException("topK must be >= 1, got: " + topK);
    }
    if (minSimilarity < 0.0 || minSimilarity > 1.0) {
      throw new IllegalArgumentException(
          "minSimilarity must be in [0.0, 1.0], got: " + minSimilarity);
    }
    if (vectorWeight < 0.0 || vectorWeight > 1.0) {
      throw new IllegalArgumentException(
          "vectorWeight must be in [0.0, 1.0], got: " + vectorWeight);
    }
    if (domain != null && domain.isBlank()) {
      throw new IllegalArgumentException("domain must not be blank");
    }
    if (caseType != null && caseType.isBlank()) {
      throw new IllegalArgumentException("caseType must not be blank when provided");
    }
    weights = Map.copyOf(weights);
    for (var entry : weights.entrySet()) {
      if (entry.getValue() < 0.0) {
        throw new IllegalArgumentException(
            "weight for feature '"
                + entry.getKey()
                + "' must be non-negative, got: "
                + entry.getValue());
      }
    }
    if (timing == null) {
      timing = CbrRetrievalTiming.PER_EVALUATION;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Map<String, String> jqFeatures = new LinkedHashMap<>();
    private Function<CaseContext, Map<String, Object>> lambdaExtractor;
    private int topK = 5;
    private double minSimilarity = 0.0;
    private final Map<String, Double> weights = new LinkedHashMap<>();
    private String domain;
    private String caseType;
    private double vectorWeight = 0.5;
    private CbrRetrievalTiming timing = CbrRetrievalTiming.PER_EVALUATION;

    /**
     * Add a JQ feature expression.
     *
     * @param name feature name
     * @param jqExpression JQ expression
     * @return this builder
     * @throws IllegalStateException if lambda extractor was already set
     */
    public Builder feature(final String name, final String jqExpression) {
      if (lambdaExtractor != null) {
        throw new IllegalStateException("Cannot mix JQ features with lambda extractor");
      }
      jqFeatures.put(name, jqExpression);
      return this;
    }

    /**
     * Set a lambda-based feature extractor.
     *
     * @param extractor extraction function
     * @return this builder
     * @throws IllegalStateException if JQ features were already added
     */
    public Builder featureExtractor(final Function<CaseContext, Map<String, Object>> extractor) {
      if (!jqFeatures.isEmpty()) {
        throw new IllegalStateException("Cannot mix lambda extractor with JQ features");
      }
      this.lambdaExtractor = extractor;
      return this;
    }

    public Builder topK(final int topK) {
      this.topK = topK;
      return this;
    }

    public Builder minSimilarity(final double minSimilarity) {
      this.minSimilarity = minSimilarity;
      return this;
    }

    /**
     * Set weight for a feature.
     *
     * @param featureName feature name
     * @param weight weight value (must be non-negative)
     * @return this builder
     */
    public Builder weight(final String featureName, final double weight) {
      this.weights.put(featureName, weight);
      return this;
    }

    public Builder domain(final String domain) {
      this.domain = domain;
      return this;
    }

    public Builder caseType(final String caseType) {
      this.caseType = caseType;
      return this;
    }

    public Builder vectorWeight(final double vectorWeight) {
      this.vectorWeight = vectorWeight;
      return this;
    }

    /**
     * Set the retrieval timing strategy.
     *
     * @param timing PER_EVALUATION (default) or CASE_LIFETIME
     * @return this builder
     */
    public Builder timing(final CbrRetrievalTiming timing) {
      this.timing = timing;
      return this;
    }

    /**
     * Build the configuration.
     *
     * @return immutable CbrConfig
     * @throws IllegalStateException if no feature extractor was configured
     */
    public CbrConfig build() {
      final FeatureExtractor extractor;
      if (!jqFeatures.isEmpty()) {
        extractor = new JqFeatureExtractor(jqFeatures);
      } else if (lambdaExtractor != null) {
        extractor = new LambdaFeatureExtractor(lambdaExtractor);
      } else {
        throw new IllegalStateException("No feature extractor configured");
      }
      return new CbrConfig(
          extractor, topK, minSimilarity, weights, domain, caseType, vectorWeight, timing);
    }
  }
}
