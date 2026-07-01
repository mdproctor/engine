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
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SequentialPlanningStrategy implements PlanningStrategy {

  @Override
  public String getId() {
    return "sequential";
  }

  @Override
  public String getName() {
    return "Sequential Strategy";
  }

  @Override
  public Uni<List<Binding>> select(
      CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible) {

    for (Binding binding : eligible) {
      Optional<PlanItem> itemOpt = plan.findPlanItemByBindingName(binding.getName());

      if (itemOpt.isEmpty()) {
        return Uni.createFrom().item(List.of(binding));
      }

      PlanItemStatus status = itemOpt.get().getStatus();

      if (status == PlanItemStatus.COMPLETED) {
        continue;
      }

      if (status == PlanItemStatus.PENDING) {
        return Uni.createFrom().item(List.of(binding));
      }

      if (status.isTerminal()) {
        return Uni.createFrom().item(List.of());
      }

      return Uni.createFrom().item(List.of());
    }

    return Uni.createFrom().item(List.of());
  }
}
