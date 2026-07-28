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
package io.casehub.engine.internal.engine;

import io.casehub.api.engine.LoopControl;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseStatus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Fallback {@link LoopControl} — fires all eligible dispatch rules concurrently for RUNNING cases.
 *
 * <p>{@code @DefaultBean}: yields automatically to {@code PlanningStrategyLoopControl} when {@code
 * casehub-engine-planning} is on the classpath. Active only in deployments without the planning
 * module.
 */
@DefaultBean
@ApplicationScoped
public class ChoreographyLoopControl implements LoopControl {

  @Override
  public List<Binding> select(final PlanExecutionContext context, final List<Binding> eligible) {
    if (context.caseStatus() != CaseStatus.RUNNING) {
      return List.of();
    }
    return eligible;
  }
}
