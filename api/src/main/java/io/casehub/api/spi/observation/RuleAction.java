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
package io.casehub.api.spi.observation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import java.time.Duration;

public sealed interface RuleAction {

  record DepositSignal(String name, double strength, @Nullable Duration halfLife)
      implements RuleAction {}

  record RegisterInterest(InterestDeclaration declaration) implements RuleAction {}

  record DeregisterInterest(String interestId) implements RuleAction {}

  record WriteContext(String key, JsonNode value) implements RuleAction {}
}
