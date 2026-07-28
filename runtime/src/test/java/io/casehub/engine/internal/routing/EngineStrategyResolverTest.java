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

  static class TestDecompositionStrategy
      implements io.casehub.engine.plan.DecompositionStrategy<Object> {
    private final String strategyId;

    TestDecompositionStrategy(String strategyId) {
      this.strategyId = strategyId;
    }

    @Override
    public String id() {
      return strategyId;
    }

    @Override
    public io.smallrye.mutiny.Uni<
            io.casehub.engine.plan.DagPlan<io.casehub.engine.plan.TaskNode.LeafTask<Object>>>
        decompose(
            io.casehub.engine.plan.TaskNode<Object> task,
            io.casehub.engine.plan.DecompositionContext<Object> context) {
      return io.smallrye.mutiny.Uni.createFrom().nullItem();
    }
  }

  @Test
  void defaultBean_winsRegardlessOfIterationOrder() {
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

  @Test
  void resolvesHumanTaskRoutingStrategy() {
    var noop = new NoOpHumanTaskRoutingStrategy();
    var resolver = buildResolver(List.of(handle(noop, true)));
    var result = resolver.resolve(io.casehub.api.spi.routing.HumanTaskRoutingStrategy.class, null);
    assertEquals("default", result.id());
  }

  @Test
  void resolvesDecompositionStrategyById() {
    var identity = new TestDecompositionStrategy("identity");
    var llm = new TestDecompositionStrategy("llm");
    var resolver = buildResolver(List.of(handle(identity, true), handle(llm, false)));

    var result = resolver.resolve(io.casehub.engine.plan.DecompositionStrategy.class, "llm");
    assertEquals("llm", result.id());
  }

  @Test
  void resolvesDecompositionStrategyDefault() {
    var identity = new TestDecompositionStrategy("identity");
    var resolver = buildResolver(List.of(handle(identity, true)));

    var result = resolver.resolve(io.casehub.engine.plan.DecompositionStrategy.class, null);
    assertEquals("identity", result.id());
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
