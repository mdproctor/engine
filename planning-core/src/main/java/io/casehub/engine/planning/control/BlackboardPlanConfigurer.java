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
package io.casehub.engine.planning.control;

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.planning.plan.CasePlanModel;

/**
 * SPI for configuring a new {@link CasePlanModel} when a case instance starts. Implement as CDI
 * {@code @ApplicationScoped}. Called exactly once per case instance when {@link
 * PlanningStrategyLoopControl} first creates its plan model. See casehubio/engine#76.
 */
public interface BlackboardPlanConfigurer {

  /**
   * Returns true if this configurer applies to cases of the given definition. Default: always
   * applies.
   */
  default boolean supports(CaseDefinition definition) {
    return true;
  }

  /**
   * Configure the plan model for a newly started case. Called once before any binding fires.
   *
   * @param plan the freshly created {@link CasePlanModel} for the case instance
   * @param context the execution context carrying the case id, definition, and current case context
   */
  void configure(CasePlanModel plan, PlanExecutionContext context);
}
