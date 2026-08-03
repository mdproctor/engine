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

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.BehavioralSignal;
import io.casehub.eidos.api.BehavioralSignalStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GoalAbandonmentEvaluator {

  static final String GOAL_CAPABILITY_SENTINEL = "__goal__";

  private final Instance<BehavioralSignalStore> signalStore;
  private final int threshold;

  @Inject
  public GoalAbandonmentEvaluator(
      Instance<BehavioralSignalStore> signalStore,
      @ConfigProperty(name = "casehub.engine.goal.abandonment-threshold", defaultValue = "5")
          int threshold) {
    this.signalStore = signalStore;
    this.threshold = threshold;
  }

  public boolean isAbandoned(String agentId, String tenancyId, String goalName) {
    if (!signalStore.isResolvable()) return false;
    int count =
        signalStore
            .get()
            .count(
                agentId, tenancyId, GOAL_CAPABILITY_SENTINEL, goalName, BehavioralSignal.DECLINE);
    return count >= threshold;
  }

  public List<AgentGoal> activeGoals(AgentDescriptor descriptor) {
    if (!signalStore.isResolvable()) return descriptor.goals();
    if (descriptor.goals().isEmpty()) return List.of();
    return descriptor.goals().stream()
        .filter(g -> !isAbandoned(descriptor.agentId(), descriptor.tenancyId(), g.name()))
        .toList();
  }
}
