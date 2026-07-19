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

import java.util.Set;

/**
 * Pre-resolved candidate groups and users for a human task binding. Passed as a separate parameter
 * to {@link HumanTaskRoutingStrategy#select}, matching the context/candidates separation convention
 * in {@link AgentRoutingStrategy} and {@link ImplementationRoutingStrategy}.
 *
 * <p>Null groups or users default to empty sets. Defensive copies are made on construction.
 *
 * @param groups resolved candidate groups
 * @param users resolved candidate users
 */
public record HumanTaskCandidates(Set<String> groups, Set<String> users) {
  public HumanTaskCandidates {
    groups = groups != null ? Set.copyOf(groups) : Set.of();
    users = users != null ? Set.copyOf(users) : Set.of();
  }
}
