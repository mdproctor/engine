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
package io.casehub.api.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.InterestDeclaration;
import io.casehub.api.spi.observation.InterestLandscape;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InterestSpaceTest {

  @Test
  void noopRegisterReturnsSentinel() {
    var reg =
        InterestSpace.NOOP.register(
            new InterestDeclaration.KeyThreshold(
                "x", InterestDeclaration.ComparisonOperator.GT, 1.0));
    assertNotNull(reg);
    assertEquals("noop-0", reg.interestId());
  }

  @Test
  void noopRegisterObserverReturnsFalse() {
    EnvironmentObserver observer =
        new EnvironmentObserver() {
          @Override
          public String observerType() {
            return "test";
          }

          @Override
          public Set<String> watchedKeys() {
            return Set.of();
          }

          @Override
          public List<Observation> observe(ObservationContext ctx) {
            return List.of();
          }
        };
    assertFalse(InterestSpace.NOOP.registerObserver(observer));
  }

  @Test
  void noopDeregisterDoesNothing() {
    assertDoesNotThrow(() -> InterestSpace.NOOP.deregister("any-id"));
  }

  @Test
  void noopMineReturnsEmpty() {
    assertTrue(InterestSpace.NOOP.mine().isEmpty());
  }

  @Test
  void noopLandscapeReturnsEmpty() {
    assertEquals(InterestLandscape.EMPTY, InterestSpace.NOOP.landscape());
  }
}
