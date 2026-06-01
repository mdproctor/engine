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
import static org.junit.jupiter.api.Assertions.fail;

import io.smallrye.mutiny.Uni;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ReactiveActorStateAggregator} has a {@code Uni<ActorStateResponse>}
 * equivalent for every {@code ActorStateResponse}-returning method on {@link ActorStateAggregator}.
 *
 * <p>Local test — avoids inverting the ledger→engine dependency direction that would result from
 * expanding casehub-ledger's BlockingReactiveParityTest.
 */
class ActorStateParityTest {

  @Test
  void reactiveAggregatorHasUniEquivalentForEveryBlockingMethod() {
    final Set<String> blockingMethodNames =
        Arrays.stream(ActorStateAggregator.class.getMethods())
            .filter(m -> m.getReturnType().equals(ActorStateResponse.class))
            .filter(m -> m.getDeclaringClass().equals(ActorStateAggregator.class))
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertFalse(
        blockingMethodNames.isEmpty(),
        "ActorStateAggregator must have at least one ActorStateResponse-returning method");

    for (final String name : blockingMethodNames) {
      final Method reactiveMethod =
          Arrays.stream(ReactiveActorStateAggregator.class.getMethods())
              .filter(m -> m.getName().equals(name))
              .findFirst()
              .orElseGet(() -> fail("ReactiveActorStateAggregator missing method: " + name));
      if (!Uni.class.equals(reactiveMethod.getReturnType())) {
        fail(
            "Method "
                + name
                + " in ReactiveActorStateAggregator must return Uni<ActorStateResponse> but returns "
                + reactiveMethod.getReturnType());
      }
    }
  }
}
