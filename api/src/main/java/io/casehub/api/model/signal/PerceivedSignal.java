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
package io.casehub.api.model.signal;

import java.time.Duration;

public record PerceivedSignal(
    String name,
    double effectiveStrength,
    int reinforcementCount,
    String lastSource,
    Duration age) {

  public PerceivedSignal {
    if (effectiveStrength < 0.0 || effectiveStrength > 1.0)
      throw new IllegalArgumentException(
          "effectiveStrength must be in [0.0, 1.0], got: " + effectiveStrength);
  }
}
