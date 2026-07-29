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
package io.casehub.api.model.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextConstraintBuilderTest {

  @Test
  void preferGroupsThenPreferUsersAccumulates() {
    var c =
        ContextConstraint.builder()
            .when(".always.true")
            .preferGroups(Set.of("managers"))
            .preferUsers(Set.of("alice"))
            .build();
    assertThat(c.effect()).isInstanceOf(ContextConstraint.Prefer.class);
    var prefer = (ContextConstraint.Prefer) c.effect();
    assertThat(prefer.groups()).containsExactly("managers");
    assertThat(prefer.users()).containsExactly("alice");
  }

  @Test
  void preferGroupsRepeatedAccumulates() {
    var c =
        ContextConstraint.builder()
            .when(".always.true")
            .preferGroups(Set.of("managers"))
            .preferGroups(Set.of("leads"))
            .build();
    var prefer = (ContextConstraint.Prefer) c.effect();
    assertThat(prefer.groups()).containsExactlyInAnyOrder("managers", "leads");
  }

  @Test
  void excludeGroupsThenExcludeUsersAccumulates() {
    var c =
        ContextConstraint.builder()
            .when(".always.true")
            .excludeGroups(Set.of("interns"))
            .excludeUsers(Set.of("bob"))
            .build();
    var exclude = (ContextConstraint.Exclude) c.effect();
    assertThat(exclude.groups()).containsExactly("interns");
    assertThat(exclude.users()).containsExactly("bob");
  }

  @Test
  void switchingEffectTypeReplaces() {
    var c =
        ContextConstraint.builder()
            .when(".always.true")
            .preferGroups(Set.of("managers"))
            .excludeUsers(Set.of("bob"))
            .build();
    assertThat(c.effect()).isInstanceOf(ContextConstraint.Exclude.class);
    var exclude = (ContextConstraint.Exclude) c.effect();
    assertThat(exclude.users()).containsExactly("bob");
    assertThat(exclude.groups()).isEmpty();
  }

  @Test
  void combinedPreferFactory() {
    var c =
        ContextConstraint.builder()
            .when(".always.true")
            .prefer(Set.of("managers"), Set.of("alice"))
            .build();
    var prefer = (ContextConstraint.Prefer) c.effect();
    assertThat(prefer.groups()).containsExactly("managers");
    assertThat(prefer.users()).containsExactly("alice");
  }

  @Test
  void combinedExcludeFactory() {
    var c =
        ContextConstraint.builder()
            .when(".always.true")
            .exclude(Set.of("interns"), Set.of("bob"))
            .build();
    var exclude = (ContextConstraint.Exclude) c.effect();
    assertThat(exclude.groups()).containsExactly("interns");
    assertThat(exclude.users()).containsExactly("bob");
  }
}
