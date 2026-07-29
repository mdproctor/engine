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

import java.util.Map;
import java.util.Set;

public record HumanTaskCandidates(
    Set<String> groups, Set<String> users, Map<String, Set<String>> groupMembership) {
  public HumanTaskCandidates {
    groups = groups != null ? Set.copyOf(groups) : Set.of();
    users = users != null ? Set.copyOf(users) : Set.of();
    groupMembership =
        groupMembership != null
            ? groupMembership.entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> Set.copyOf(e.getValue())))
            : Map.of();
  }

  public Set<String> allUsers() {
    if (groupMembership.isEmpty()) {
      return users;
    }
    var all = new java.util.LinkedHashSet<>(users);
    groupMembership.values().forEach(all::addAll);
    return Set.copyOf(all);
  }

  public static HumanTaskCandidates of(Set<String> groups, Set<String> users) {
    return new HumanTaskCandidates(groups, users, Map.of());
  }
}
