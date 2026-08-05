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
package io.casehub.api.spi.routing;

/**
 * Pre-probed agent health status, mapped from {@code casehub-eidos-api} {@code CapabilityStatus} at
 * candidate construction time.
 *
 * <p>{@code UNAVAILABLE} and {@code EXCLUDED} workers are filtered before the candidate list is
 * built — they never reach {@link AgentRoutingStrategy#select}. This enum exists so {@code
 * casehub-engine-api} does not take a compile-time dependency on {@code casehub-eidos-api}.
 *
 * <p>Enum declaration order reflects severity (softest first): {@code READY} > {@code
 * BEHAVIORAL_VIOLATION} > {@code EPISTEMICALLY_WEAK} > {@code DEGRADED}.
 */
public enum AgentHealth {
  /** Agent is available and operating normally. */
  READY,
  /** Agent has behavioral compliance violations but is still operational — soft demotion. */
  BEHAVIORAL_VIOLATION,
  /** Agent's epistemic coverage for this capability is uncertain — keep, consider demoting. */
  EPISTEMICALLY_WEAK,
  /** Agent is available but operating in a degraded state — keep, consider demoting. */
  DEGRADED
}
