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
import io.casehub.platform.api.routing.NamedStrategy;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * Selects which eligible {@link Binding}s to fire and in what order, optionally reading and writing
 * {@link CasePlanModel} control state.
 *
 * <p>Returns {@code Uni} — implementations may perform non-blocking I/O (e.g. EventLog queries)
 * before returning. See casehubio/engine#76. Async strategy use cases tracked in
 * casehubio/engine#82.
 *
 * <p>Contract (enforced by {@link PlanningStrategyContractTest}):
 *
 * <ul>
 *   <li>Never return bindings not in {@code eligible}
 *   <li>Never return null — return empty list to suppress all firing
 *   <li>Handle empty {@code eligible} gracefully
 * </ul>
 */
public interface PlanningStrategy extends NamedStrategy {
  @Override
  String id();

  String getName();

  Uni<List<Binding>> select(
      CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible);
}
