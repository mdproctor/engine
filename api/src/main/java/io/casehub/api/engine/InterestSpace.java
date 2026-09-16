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

import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.InterestDeclaration;
import io.casehub.api.spi.observation.InterestLandscape;
import io.casehub.api.spi.observation.InterestRegistration;
import java.time.Instant;
import java.util.List;

public interface InterestSpace {

  InterestRegistration register(InterestDeclaration interest);

  boolean registerObserver(EnvironmentObserver observer);

  void deregister(String interestId);

  List<InterestRegistration> mine();

  InterestLandscape landscape();

  InterestSpace NOOP =
      new InterestSpace() {
        @Override
        public InterestRegistration register(InterestDeclaration interest) {
          return new InterestRegistration("noop-0", interest, Instant.now());
        }

        @Override
        public boolean registerObserver(EnvironmentObserver observer) {
          return false;
        }

        @Override
        public void deregister(String interestId) {}

        @Override
        public List<InterestRegistration> mine() {
          return List.of();
        }

        @Override
        public InterestLandscape landscape() {
          return InterestLandscape.EMPTY;
        }
      };
}
