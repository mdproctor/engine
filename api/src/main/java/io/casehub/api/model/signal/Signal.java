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
import java.time.Instant;
import java.util.Set;

public record Signal(
    String name,
    double strength,
    Instant firstDeposited,
    Instant lastReinforced,
    Duration halfLife,
    String lastSource,
    int reinforcementCount,
    boolean expired,
    Set<String> sources) {

  public Signal {
    if (strength < 0.0 || strength > 1.0)
      throw new IllegalArgumentException("strength must be in [0.0, 1.0], got: " + strength);
    if (halfLife.isNegative() || halfLife.isZero())
      throw new IllegalArgumentException("halfLife must be positive, got: " + halfLife);
  }

  public Signal(
      String name,
      double strength,
      Instant firstDeposited,
      Instant lastReinforced,
      Duration halfLife,
      String lastSource,
      int reinforcementCount,
      boolean expired) {
    this(
        name,
        strength,
        firstDeposited,
        lastReinforced,
        halfLife,
        lastSource,
        reinforcementCount,
        expired,
        lastSource != null ? Set.of(lastSource) : Set.of());
  }
}
