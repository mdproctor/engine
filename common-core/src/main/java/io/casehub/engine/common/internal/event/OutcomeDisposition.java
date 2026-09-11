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
package io.casehub.engine.common.internal.event;

/**
 * Pre-resolved action determined by {@link io.casehub.api.model.OutcomePolicy} for a non-success
 * worker outcome. Published on {@link WorkerOutcomeResolvedEvent} so the blackboard handler can act
 * without needing OutcomePolicy resolution.
 *
 * <ul>
 *   <li>{@code REROUTE} — mark PlanItem FAULTED, publish CONTEXT_CHANGED (triggers re-dispatch)
 *   <li>{@code EXHAUSTED} — reroute attempts exceeded; PlanItem FAULTED + stage autocomplete +
 *       CONTEXT_CHANGED
 *   <li>{@code FAULT} — OutcomePolicy says fault immediately; PlanItem FAULTED + stage autocomplete
 *       (no CONTEXT_CHANGED — case is terminal)
 * </ul>
 */
public enum OutcomeDisposition {
  REROUTE,
  EXHAUSTED,
  FAULT
}
