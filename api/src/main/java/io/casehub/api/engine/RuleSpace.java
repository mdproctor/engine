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

import io.casehub.api.spi.observation.LocalRule;
import io.casehub.api.spi.observation.RuleFiring;
import io.casehub.api.spi.observation.RuleRegistration;
import java.time.Instant;
import java.util.List;

public interface RuleSpace {

  RuleRegistration register(LocalRule rule);

  void deregister(String ruleId);

  List<RuleRegistration> mine();

  List<RuleFiring> lastFired();

  RuleSpace NOOP =
      new RuleSpace() {
        @Override
        public RuleRegistration register(LocalRule rule) {
          return new RuleRegistration(rule.id(), rule, Instant.now());
        }

        @Override
        public void deregister(String ruleId) {}

        @Override
        public List<RuleRegistration> mine() {
          return List.of();
        }

        @Override
        public List<RuleFiring> lastFired() {
          return List.of();
        }
      };
}
