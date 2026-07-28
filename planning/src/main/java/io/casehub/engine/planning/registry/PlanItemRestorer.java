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
package io.casehub.engine.planning.registry;

import io.casehub.api.model.BindingTarget;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.planning.plan.PlanItem;

/**
 * Package-private utility — converts {@link PlanItemRecord} to {@link PlanItem} during registry
 * hydration.
 *
 * <p>Keeps {@link BlackboardRegistry} free of {@link HumanTaskTarget} and evaluator imports.
 */
class PlanItemRestorer {

  PlanItem restore(PlanItemRecord r) {
    BindingTarget target =
        r.targetType() == TargetType.HUMAN_TASK
            ? buildHumanTaskTarget(r.outputMappingExpression())
            : null;
    io.casehub.api.model.ExecutorRef executor =
        r.executorName() != null
            ? io.casehub.api.model.ExecutorRef.of(r.executorName(), r.executorDescription())
            : null;
    return PlanItem.restore(
        r.planItemId(),
        r.bindingName(),
        executor,
        target,
        r.status(),
        r.createdAt(),
        r.description());
  }

  private HumanTaskTarget buildHumanTaskTarget(String expr) {
    HumanTaskTarget.Builder b = HumanTaskTarget.inline().title("[restored]");
    if (expr != null) {
      b = b.outputMapping(expr);
    }
    return b.build();
  }
}
