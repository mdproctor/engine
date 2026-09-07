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
package io.casehub.api.spi;

/**
 * External dispatch budget for session-level capacity providers. The engine consults this SPI
 * before dispatching bindings to avoid wasted work (routing, EventLog, PlanItem creation) when the
 * provider's capacity is exhausted.
 *
 * <p>Advisory semantics — the capacity query prevents most wasted work, but cross-case TOCTOU races
 * can briefly over-dispatch. {@code WorkerExecutionManager.submit()} remains the hard gate.
 *
 * <p>Default implementation returns {@code Integer.MAX_VALUE} (unlimited). Consumer implementations
 * (e.g. claudony session pool) provide {@code @ApplicationScoped} beans that displace the default.
 */
public interface DispatchBudget {
  int availableCapacity(DispatchBudgetQuery query);
}
