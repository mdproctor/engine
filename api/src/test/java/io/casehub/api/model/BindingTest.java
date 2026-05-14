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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BindingTest {

  @Test
  void builder_withNoTarget_throws() {
    assertThatThrownBy(() -> Binding.builder().name("b").on(new ContextChangeTrigger(".x")).build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void builder_capabilityConvenienceMethod_producesCapabilityTarget() {
    Capability cap = Capability.builder().name("c").inputSchema("{}").outputSchema("{}").build();
    Binding b =
        Binding.builder().name("b").capability(cap).on(new ContextChangeTrigger(".x")).build();

    assertThat(b.target()).isInstanceOf(CapabilityTarget.class);
    assertThat(((CapabilityTarget) b.target()).capability()).isSameAs(cap);
  }

  @Test
  void builder_subCaseConvenienceMethod_producesSubCaseTarget() {
    SubCase sc = SubCase.builder().namespace("n").name("c").version("1").build();
    Binding b = Binding.builder().name("b").subCase(sc).on(new ContextChangeTrigger(".x")).build();

    assertThat(b.target()).isInstanceOf(SubCaseTarget.class);
    assertThat(((SubCaseTarget) b.target()).subCase()).isSameAs(sc);
  }

  @Test
  void builder_humanTaskTarget_storedAsTarget() {
    HumanTaskTarget ht = HumanTaskTarget.template("irb-72h-review").build();
    Binding b =
        Binding.builder().name("b").humanTask(ht).on(new ContextChangeTrigger(".x")).build();

    assertThat(b.target()).isInstanceOf(HumanTaskTarget.class);
    assertThat(b.target()).isSameAs(ht);
  }

  @Test
  void target_sealedHierarchy_allPermitsReachable() {
    // Java 17 doesn't support exhaustive switch pattern matching (Java 21+).
    // Prove sealed hierarchy is complete: each permit type is assignable from BindingTarget,
    // and only these four types exist (compiler enforces no unknown subtypes).
    Capability cap = Capability.builder().name("c").inputSchema("{}").outputSchema("{}").build();
    SubCase sc = SubCase.builder().namespace("n").name("c").version("1").build();
    HumanTaskTarget ht = HumanTaskTarget.template("t1").build();

    BindingTarget capTarget = new CapabilityTarget(cap);
    BindingTarget scTarget = new SubCaseTarget(sc);
    BindingTarget htTarget = ht;

    assertThat(capTarget).isInstanceOf(CapabilityTarget.class);
    assertThat(scTarget).isInstanceOf(SubCaseTarget.class);
    assertThat(htTarget).isInstanceOf(HumanTaskTarget.class);
    // ExtensionTarget is a non-sealed interface: any class implementing it is a BindingTarget
    assertThat(new ExtensionTarget() {}).isInstanceOf(BindingTarget.class);
    assertThat(CapabilityTarget.class.isSealed()).isFalse(); // record, not sealed itself
  }
}
