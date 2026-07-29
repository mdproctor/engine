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
package io.casehub.api.model.routing;

import org.jspecify.annotations.Nullable;

public record WorkloadConstraint(
    @Nullable Integer maxActiveTaskCount, @Nullable Double loadBalanceWeight) {

  public WorkloadConstraint {
    if (maxActiveTaskCount != null && maxActiveTaskCount < 0) {
      throw new IllegalArgumentException(
          "maxActiveTaskCount must be non-negative, got: " + maxActiveTaskCount);
    }
    if (loadBalanceWeight != null && (loadBalanceWeight < 0.0 || loadBalanceWeight > 1.0)) {
      throw new IllegalArgumentException(
          "loadBalanceWeight must be in range [0.0, 1.0], got: " + loadBalanceWeight);
    }
    if (maxActiveTaskCount == null && loadBalanceWeight == null) {
      throw new IllegalArgumentException(
          "at least one of maxActiveTaskCount or loadBalanceWeight must be set");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Integer maxActiveTaskCount;
    private Double loadBalanceWeight;

    private Builder() {}

    public Builder maxActiveTaskCount(int maxActiveTaskCount) {
      this.maxActiveTaskCount = maxActiveTaskCount;
      return this;
    }

    public Builder loadBalanceWeight(double loadBalanceWeight) {
      this.loadBalanceWeight = loadBalanceWeight;
      return this;
    }

    public WorkloadConstraint build() {
      return new WorkloadConstraint(maxActiveTaskCount, loadBalanceWeight);
    }
  }
}
