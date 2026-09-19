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
package io.casehub.api.model.stigmergy;

import jakarta.annotation.Nullable;

public record RoleDomainWeights(
    @Nullable Double perception,
    @Nullable Double communication,
    @Nullable Double decision,
    @Nullable Double effect) {

  public static final RoleDomainWeights EQUAL = new RoleDomainWeights(0.25, 0.25, 0.25, 0.25);

  public double[] normalized() {
    double p = perception != null ? perception : 0.25;
    double c = communication != null ? communication : 0.25;
    double d = decision != null ? decision : 0.25;
    double e = effect != null ? effect : 0.25;
    double sum = p + c + d + e;
    if (sum <= 0) return new double[] {0.25, 0.25, 0.25, 0.25};
    return new double[] {p / sum, c / sum, d / sum, e / sum};
  }
}
