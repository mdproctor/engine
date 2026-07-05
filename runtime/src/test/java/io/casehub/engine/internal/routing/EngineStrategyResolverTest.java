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
package io.casehub.engine.internal.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.platform.api.routing.NamedStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineStrategyResolverTest {

  interface TestStrategy extends NamedStrategy {}

  static class StrategyA implements TestStrategy {
    @Override
    public String id() {
      return "a";
    }
  }

  static class StrategyB implements TestStrategy {
    @Override
    public String id() {
      return "b";
    }
  }

  @Test
  void defaultBean_winsRegardlessOfIterationOrder() {
    // StrategyA is NOT @DefaultBean, StrategyB IS @DefaultBean.
    // Even though A iterates first, B should be the default.
    var resolver =
        buildResolver(List.of(handle(new StrategyA(), false), handle(new StrategyB(), true)));

    TestStrategy defaultStrategy = resolver.defaultStrategy(TestStrategy.class);
    assertEquals("b", defaultStrategy.id());
  }

  @Test
  void noDefaultBean_fallsBackToFirst() {
    var resolver =
        buildResolver(List.of(handle(new StrategyA(), false), handle(new StrategyB(), false)));

    TestStrategy defaultStrategy = resolver.defaultStrategy(TestStrategy.class);
    assertEquals("a", defaultStrategy.id());
  }

  @Test
  void duplicateDefaultBean_throws() {
    assertThrows(
        IllegalStateException.class,
        () -> buildResolver(List.of(handle(new StrategyA(), true), handle(new StrategyB(), true))));
  }

  @Test
  void defaultBean_registeredFirst_notOverwrittenByNonDefault() {
    var resolver =
        buildResolver(List.of(handle(new StrategyA(), true), handle(new StrategyB(), false)));

    TestStrategy defaultStrategy = resolver.defaultStrategy(TestStrategy.class);
    assertEquals("a", defaultStrategy.id());
  }

  private EngineStrategyResolver buildResolver(
      List<EngineStrategyResolver.TestHandle<? extends NamedStrategy>> handles) {
    return EngineStrategyResolver.forTest(handles);
  }

  private <T extends NamedStrategy> EngineStrategyResolver.TestHandle<T> handle(
      T strategy, boolean isDefaultBean) {
    return new EngineStrategyResolver.TestHandle<>(strategy, isDefaultBean);
  }
}
