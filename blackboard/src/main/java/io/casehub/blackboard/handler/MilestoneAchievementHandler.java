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
package io.casehub.blackboard.handler;

import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneReachedEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Promotes milestone to ACHIEVED in the {@link io.casehub.blackboard.plan.CasePlanModel} when a
 * {@code MilestoneReachedEvent} fires. Only acts if a plan model exists for the case — pure
 * choreography cases have no plan model.
 *
 * <p>Uses {@code MILESTONE_REACHED} which is published via {@code eventBus.publish()} (fan-out) —
 * this handler coexists with the engine's existing milestone processing. See casehubio/engine#76.
 * Milestone alignment: casehubio/engine#84.
 *
 * <p><strong>Internal Event Use:</strong> {@link MilestoneReachedEvent} is in the {@code
 * engine.internal.event} package. For event consumption via {@code @ConsumeEvent}, the handler must
 * use the same event type that was published by the engine — there is no public API alternative.
 * Using internal event types in {@code @ConsumeEvent} handlers is the accepted pattern in this
 * codebase.
 */
@ApplicationScoped
public class MilestoneAchievementHandler {

  private final BlackboardRegistry registry;

  @Inject
  public MilestoneAchievementHandler(BlackboardRegistry registry) {
    this.registry = registry;
  }

  @ConsumeEvent(EventBusAddresses.MILESTONE_REACHED)
  public Uni<Void> onMilestoneReached(MilestoneReachedEvent event) {
    registry
        .get(event.caseInstance().getUuid())
        .ifPresent(plan -> plan.achieveMilestone(event.milestone().getName()));
    return Uni.createFrom().voidItem();
  }
}
