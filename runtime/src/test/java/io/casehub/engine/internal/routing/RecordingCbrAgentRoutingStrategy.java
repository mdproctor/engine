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

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.quarkus.arc.Unremovable;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording {@link AgentRoutingStrategy} for CBR integration tests. Captures every {@link
 * AgentRoutingContext} (including the {@code experiences} list) passed to {@code select()}, then
 * assigns the first candidate.
 *
 * <p>Registered as a plain {@code @ApplicationScoped} bean — the {@link EngineStrategyResolver}
 * discovers it via {@code @Any Instance<AgentRoutingStrategy>} and indexes it by id {@code
 * "cbr-recording"}. Test case definitions set {@code agentRouting("cbr-recording")} to route
 * through this strategy.
 *
 * <p>Refs casehubio/engine#478.
 */
@Alternative
@Priority(100)
@ApplicationScoped
@Unremovable
public class RecordingCbrAgentRoutingStrategy implements AgentRoutingStrategy {

  static final List<AgentRoutingContext> capturedContexts = new CopyOnWriteArrayList<>();

  static void reset() {
    capturedContexts.clear();
  }

  @Override
  public String id() {
    return "cbr-recording";
  }

  @Override
  public Uni<AgentAssignment> select(AgentRoutingContext context, List<AgentCandidate> candidates) {
    capturedContexts.add(context);
    if (candidates.isEmpty()) {
      return Uni.createFrom().item(AgentAssignment.unresolvable("no candidates"));
    }
    return Uni.createFrom()
        .item(AgentAssignment.assign(candidates.get(0).workerId(), "cbr-recording-test"));
  }
}
