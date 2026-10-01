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
package io.casehub.engine.internal.work;

/**
 * CDI documentation anchor for quarkus-work-core bean discovery.
 *
 * <h2>Why this class has no producers</h2>
 *
 * <p>{@code quarkus-work-core} annotates its key beans directly with {@code @ApplicationScoped}:
 *
 * <ul>
 *   <li>{@code WorkBroker} — trigger gate, capability filter, strategy dispatch
 *   <li>{@code LeastLoadedStrategy} — routes work to the least-loaded worker
 *   <li>{@code ClaimFirstStrategy} — leaves the pool open for claim-first behaviour
 *   <li>{@code NoOpWorkerRegistry} — group resolution no-op; workers come from CaseDefinition
 * </ul>
 *
 * <p>Quarkus Arc discovers and registers all of the above automatically. Adding {@code @Produces}
 * methods here for the same types would create duplicate CDI beans and cause startup failures.
 *
 * <h2>Injection rule: always inject the concrete strategy type</h2>
 *
 * <p>Because two {@code WorkerSelectionStrategy} implementations ({@code LeastLoadedStrategy} and
 * {@code ClaimFirstStrategy}) are both active CDI beans, injecting by the interface type causes an
 * {@code AmbiguousResolutionException} at startup. Always inject the concrete type you want:
 *
 * <pre>{@code
 * // Correct — unambiguous:
 * @Inject LeastLoadedStrategy selectionStrategy;
 *
 * // Wrong — ambiguous, fails at startup:
 * @Inject WorkerSelectionStrategy selectionStrategy;
 * }</pre>
 *
 * <p>All casehub-engine injection points follow this rule.
 */
public class WorkCdi {
  // Intentionally empty. quarkus-work-core handles its own bean registration.
}
