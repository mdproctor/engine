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
package io.casehub.blackboard.control;

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.blackboard.plan.CasePlanModel;
import io.quarkus.arc.Unremovable;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Default {@link PlanningStrategy} — returns all eligible bindings unchanged, assigns equal
 * priority. Equivalent to choreography but routed through the plan model, enabling CasePlanModel
 * state to accumulate for custom strategies. See casehubio/engine#76. Epic casehubio/engine#30.
 */
@ApplicationScoped
@Unremovable
public class DefaultPlanningStrategy implements PlanningStrategy {

  @Override
  public String id() {
    return "default";
  }

  @Override
  public String getName() {
    return "Default Equal-Priority Strategy";
  }

  @Override
  public Uni<List<Binding>> select(
      CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible) {
    List<Binding> deduped = eligible.stream().distinct().toList();
    return Uni.createFrom().item(deduped);
  }
}
