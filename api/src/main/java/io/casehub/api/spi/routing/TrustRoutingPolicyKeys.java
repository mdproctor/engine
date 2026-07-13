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
package io.casehub.api.spi.routing;

import io.casehub.platform.api.preferences.PreferenceKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TrustRoutingPolicyKeys {

  private final PreferenceKey<DoublePreference> threshold;
  private final PreferenceKey<IntPreference> minimumObservations;
  private final PreferenceKey<DoublePreference> borderlineMargin;
  private final PreferenceKey<DoublePreference> blendFactor;
  private final PreferenceKey<DoublePreference> cbrWeight;
  private final Map<String, PreferenceKey<DoublePreference>> floorKeys;

  private final String scopePrefix;

  private TrustRoutingPolicyKeys(
      String scopePrefix, Map<String, PreferenceKey<DoublePreference>> floorKeys) {
    this.scopePrefix = scopePrefix;
    this.threshold =
        new PreferenceKey<>(
            scopePrefix, "threshold", DoublePreference.of(0.0), DoublePreference::parse);
    this.minimumObservations =
        new PreferenceKey<>(
            scopePrefix, "minimum-observations", IntPreference.of(0), IntPreference::parse);
    this.borderlineMargin =
        new PreferenceKey<>(
            scopePrefix, "borderline-margin", DoublePreference.of(0.0), DoublePreference::parse);
    this.blendFactor =
        new PreferenceKey<>(
            scopePrefix, "blend-factor", DoublePreference.of(0.0), DoublePreference::parse);
    this.cbrWeight =
        new PreferenceKey<>(
            scopePrefix, "cbr-weight", DoublePreference.of(0.0), DoublePreference::parse);
    this.floorKeys = Collections.unmodifiableMap(floorKeys);
  }

  public static TrustRoutingPolicyKeys create(String scopePrefix) {
    return new TrustRoutingPolicyKeys(scopePrefix, Map.of());
  }

  public TrustRoutingPolicyKeys withFloor(String dimension, String keySuffix) {
    var newFloors = new LinkedHashMap<>(floorKeys);
    newFloors.put(
        dimension,
        new PreferenceKey<>(
            scopePrefix, "floor." + keySuffix, DoublePreference.of(0.0), DoublePreference::parse));
    return new TrustRoutingPolicyKeys(scopePrefix, newFloors);
  }

  public PreferenceKey<DoublePreference> threshold() {
    return threshold;
  }

  public PreferenceKey<IntPreference> minimumObservations() {
    return minimumObservations;
  }

  public PreferenceKey<DoublePreference> borderlineMargin() {
    return borderlineMargin;
  }

  public PreferenceKey<DoublePreference> blendFactor() {
    return blendFactor;
  }

  public PreferenceKey<DoublePreference> cbrWeight() {
    return cbrWeight;
  }

  public Map<String, PreferenceKey<DoublePreference>> allFloorKeys() {
    return floorKeys;
  }
}
