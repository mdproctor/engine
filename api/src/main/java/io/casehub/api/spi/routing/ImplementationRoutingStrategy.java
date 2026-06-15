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

import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * Selects which implementation(s) handle a capability when multiple bindings target the same
 * capability. Symmetric to {@link AgentRoutingStrategy} which selects which worker instance handles
 * a task.
 *
 * <p>Returns {@link Uni} per protocol PP-20260529-9f9627 — implementations may perform blocking I/O
 * (trust lookups, external classification).
 *
 * <p>Refs casehubio/engine#476.
 */
public interface ImplementationRoutingStrategy {

  Uni<ImplementationSelection> select(
      ImplementationRoutingContext context, List<ImplementationCandidate> candidates);
}
