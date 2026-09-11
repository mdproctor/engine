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
package io.casehub.actorstate;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ActorStateAggregator} has at least one {@code ActorStateResponse}-returning
 * method -- a structural sanity check.
 *
 * <p>The reactive parity test was removed when the blocking-first migration eliminated
 * ReactiveActorStateAggregator.
 */
class ActorStateParityTest {

  @Test
  void aggregatorHasAtLeastOneActorStateResponseMethod() {
    final Set<String> methodNames =
        Arrays.stream(ActorStateAggregator.class.getMethods())
            .filter(m -> m.getReturnType().equals(ActorStateResponse.class))
            .filter(m -> m.getDeclaringClass().equals(ActorStateAggregator.class))
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertFalse(
        methodNames.isEmpty(),
        "ActorStateAggregator must have at least one ActorStateResponse-returning method");
  }
}
