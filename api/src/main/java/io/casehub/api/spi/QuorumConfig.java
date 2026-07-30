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
package io.casehub.api.spi;

import io.casehub.api.model.OnThresholdReached;
import org.jspecify.annotations.Nullable;

public record QuorumConfig(
    int instances,
    int required,
    @Nullable OnThresholdReached onThresholdReached,
    boolean allowSameAssignee) {

  public QuorumConfig {
    if (instances < 2) {
      throw new IllegalArgumentException("instances must be >= 2, got " + instances);
    }
    if (required < 1 || required > instances) {
      throw new IllegalArgumentException("required must be 1.." + instances + ", got " + required);
    }
  }

  public static QuorumConfig majority(int candidateCount) {
    return new QuorumConfig(candidateCount, (candidateCount / 2) + 1, null, false);
  }

  public static QuorumConfig unanimous(int candidateCount) {
    return new QuorumConfig(candidateCount, candidateCount, null, false);
  }

  public static QuorumConfig atLeast(int candidateCount, int required) {
    return new QuorumConfig(candidateCount, required, null, false);
  }
}
