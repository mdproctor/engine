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

import io.casehub.worker.api.Capability;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompensationBindingValidationTest {

  private static Binding binding(String name) {
    return Binding.builder()
        .name(name)
        .capability(Capability.of(name, ".", "."))
        .on(new ContextChangeTrigger("$"))
        .build();
  }

  private static Binding bindingWithCompensate(String name, String compensateRef) {
    return Binding.builder()
        .name(name)
        .capability(Capability.of(name, ".", "."))
        .on(new ContextChangeTrigger("$"))
        .compensateRef(compensateRef)
        .build();
  }

  private static Binding compensationBinding(String name) {
    return Binding.builder()
        .name(name)
        .capability(Capability.of(name, ".", "."))
        .on(new ContextChangeTrigger("$"))
        .compensation(true)
        .build();
  }

  @Test
  void validCompensationPair_noError() {
    List<Binding> bindings =
        List.of(
            bindingWithCompensate("irb-review", "irb-reversal"),
            compensationBinding("irb-reversal"));
    assertThat(Binding.validateCompensationBindings(bindings)).isEmpty();
  }

  @Test
  void compensateRef_missingTarget_returnsError() {
    List<Binding> bindings = List.of(bindingWithCompensate("irb-review", "nonexistent"));
    List<String> errors = Binding.validateCompensationBindings(bindings);
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).contains("irb-review").contains("nonexistent");
  }

  @Test
  void selfCompensation_returnsError() {
    List<Binding> bindings = List.of(bindingWithCompensate("irb-review", "irb-review"));
    List<String> errors = Binding.validateCompensationBindings(bindings);
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).contains("itself");
  }

  @Test
  void circularCompensation_returnsError() {
    Binding a =
        Binding.builder()
            .name("a")
            .capability(Capability.of("a", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .compensateRef("b")
            .build();
    Binding b =
        Binding.builder()
            .name("b")
            .capability(Capability.of("b", ".", "."))
            .on(new ContextChangeTrigger("$"))
            .compensateRef("a")
            .build();
    List<String> errors = Binding.validateCompensationBindings(List.of(a, b));
    assertThat(errors).anyMatch(e -> e.toLowerCase().contains("circular"));
  }

  @Test
  void noCompensation_noErrors() {
    List<Binding> bindings = List.of(binding("step-a"), binding("step-b"));
    assertThat(Binding.validateCompensationBindings(bindings)).isEmpty();
  }

  @Test
  void orphanedCompensationBinding_returnsWarning() {
    List<Binding> bindings = List.of(binding("step-a"), compensationBinding("orphaned-reversal"));
    List<String> errors = Binding.validateCompensationBindings(bindings);
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).contains("orphaned-reversal");
  }
}
