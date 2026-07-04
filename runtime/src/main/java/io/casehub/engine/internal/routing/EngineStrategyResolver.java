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

import io.casehub.platform.api.routing.NamedStrategy;
import io.casehub.platform.api.routing.StrategyResolver;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Engine-local {@link StrategyResolver} using Quarkus ARC {@code @All} discovery.
 *
 * <p>Overrides {@code DefaultStrategyResolver} from casehub-platform because the platform resolver
 * uses {@code @Any Instance<NamedStrategy>} which may not discover all beans in Quarkus ARC's
 * build-time pruning context. {@code @All List<NamedStrategy>} is the Quarkus-idiomatic way to
 * discover all beans of a type.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class EngineStrategyResolver implements StrategyResolver {

  private final Map<Class<?>, Map<String, NamedStrategy>> index;
  private final Map<Class<?>, NamedStrategy> defaults;

  @Inject
  public EngineStrategyResolver(
      @Any Instance<io.casehub.api.spi.routing.AgentRoutingStrategy> agentStrategies,
      @Any Instance<io.casehub.api.spi.routing.ImplementationRoutingStrategy> implStrategies,
      @Any Instance<io.casehub.api.spi.routing.CandidateMatchingStrategy> matchStrategies,
      @Any Instance<io.casehub.api.spi.routing.CandidateSetStrategy> candidateSetStrategies,
      @Any
          Instance<io.casehub.engine.common.spi.scheduler.WorkerExecutionRoutingStrategy>
              execStrategies,
      @Any Instance<io.casehub.api.spi.routing.TrustRoutingPolicyProvider> trustStrategies) {
    this.index = new HashMap<>();
    this.defaults = new HashMap<>();

    List<NamedStrategy> strategyList = new java.util.ArrayList<>();
    agentStrategies.forEach(strategyList::add);
    implStrategies.forEach(strategyList::add);
    matchStrategies.forEach(strategyList::add);
    candidateSetStrategies.forEach(strategyList::add);
    execStrategies.forEach(strategyList::add);
    trustStrategies.forEach(strategyList::add);

    org.jboss.logging.Logger.getLogger(EngineStrategyResolver.class)
        .infof(
            "EngineStrategyResolver discovered %d strategy beans: %s",
            strategyList.size(),
            strategyList.stream()
                .map(s -> s.getClass().getSimpleName() + "(id=" + s.id() + ")")
                .toList());

    for (NamedStrategy strategy : strategyList) {
      for (Class<?> iface : resolveStrategyTypes(strategy.getClass())) {
        Map<String, NamedStrategy> byId =
            this.index.computeIfAbsent(iface, k -> new LinkedHashMap<>());
        NamedStrategy existing = byId.put(strategy.id(), strategy);
        if (existing != null) {
          throw new IllegalStateException(
              "Duplicate strategy id '"
                  + strategy.id()
                  + "' for type "
                  + iface.getSimpleName()
                  + ": "
                  + existing.getClass().getName()
                  + " and "
                  + strategy.getClass().getName());
        }
        this.defaults.putIfAbsent(iface, strategy);
      }
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends NamedStrategy> T resolve(Class<T> type, String id) {
    if (id == null) {
      return defaultStrategy(type);
    }
    Map<String, NamedStrategy> byId = this.index.get(type);
    if (byId != null && byId.containsKey(id)) {
      return (T) byId.get(id);
    }
    Set<?> available = byId == null ? Set.of() : byId.keySet();
    throw new IllegalArgumentException(
        "No strategy with id '"
            + id
            + "' for type "
            + type.getSimpleName()
            + ". Available: "
            + available);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends NamedStrategy> Optional<T> find(Class<T> type, String id) {
    Map<String, NamedStrategy> byId = this.index.get(type);
    return byId == null ? Optional.empty() : Optional.ofNullable((T) byId.get(id));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends NamedStrategy> T defaultStrategy(Class<T> type) {
    T def = (T) this.defaults.get(type);
    if (def == null) {
      throw new IllegalArgumentException("No default strategy for type " + type.getSimpleName());
    }
    return def;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends NamedStrategy> List<T> available(Class<T> type) {
    Map<String, NamedStrategy> byId = this.index.get(type);
    return byId == null ? List.of() : byId.values().stream().map(s -> (T) s).toList();
  }

  private static Set<Class<?>> resolveStrategyTypes(Class<?> clazz) {
    Set<Class<?>> result = new LinkedHashSet<>();
    for (Class<?> iface : clazz.getInterfaces()) {
      if (NamedStrategy.class.isAssignableFrom(iface) && iface != NamedStrategy.class) {
        result.add(iface);
      }
    }
    Class<?> superclass = clazz.getSuperclass();
    if (superclass != null && superclass != Object.class) {
      result.addAll(resolveStrategyTypes(superclass));
    }
    for (Class<?> iface : clazz.getInterfaces()) {
      result.addAll(resolveStrategyTypes(iface));
    }
    result.remove(NamedStrategy.class);
    return result;
  }
}
