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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HumanTaskCandidatesTest {

  @Test
  void validConstruction() {
    var c = HumanTaskCandidates.of(Set.of("group-a"), Set.of("user-1"));
    assertThat(c.groups()).containsExactly("group-a");
    assertThat(c.users()).containsExactly("user-1");
    assertThat(c.groupMembership()).isEmpty();
  }

  @Test
  void nullGroupsDefaultsToEmpty() {
    var c = new HumanTaskCandidates(null, Set.of("user-1"), null);
    assertThat(c.groups()).isEmpty();
  }

  @Test
  void nullUsersDefaultsToEmpty() {
    var c = new HumanTaskCandidates(Set.of("group-a"), null, null);
    assertThat(c.users()).isEmpty();
  }

  @Test
  void defensiveCopy() {
    var groups = new HashSet<>(Set.of("g"));
    var c = new HumanTaskCandidates(groups, Set.of(), Map.of());
    groups.add("g2");
    assertThat(c.groups()).doesNotContain("g2");
  }

  @Test
  void allUsersUnionOfDirectAndGroupExpanded() {
    var c =
        new HumanTaskCandidates(
            Set.of("managers"), Set.of("alice"), Map.of("managers", Set.of("bob", "charlie")));
    assertThat(c.allUsers()).containsExactlyInAnyOrder("alice", "bob", "charlie");
  }

  @Test
  void allUsersDeduplicatesOverlap() {
    var c =
        new HumanTaskCandidates(
            Set.of("managers"), Set.of("alice"), Map.of("managers", Set.of("alice", "bob")));
    assertThat(c.allUsers()).containsExactlyInAnyOrder("alice", "bob");
  }

  @Test
  void allUsersFallsBackToUsersWhenNoMembership() {
    var c = new HumanTaskCandidates(Set.of("managers"), Set.of("alice"), Map.of());
    assertThat(c.allUsers()).containsExactly("alice");
  }

  @Test
  void groupMembershipDefensiveCopy() {
    var members = new java.util.HashMap<String, Set<String>>();
    members.put("g", Set.of("u1"));
    var c = new HumanTaskCandidates(Set.of("g"), Set.of(), members);
    members.put("g2", Set.of("u2"));
    assertThat(c.groupMembership()).doesNotContainKey("g2");
  }

  @Test
  void nullGroupMembershipDefaultsToEmpty() {
    var c = new HumanTaskCandidates(Set.of(), Set.of("alice"), null);
    assertThat(c.groupMembership()).isEmpty();
    assertThat(c.allUsers()).containsExactly("alice");
  }

  @Test
  void ofFactoryCreatesEmptyMembership() {
    var c = HumanTaskCandidates.of(Set.of("g"), Set.of("u"));
    assertThat(c.groups()).containsExactly("g");
    assertThat(c.users()).containsExactly("u");
    assertThat(c.groupMembership()).isEmpty();
  }
}
