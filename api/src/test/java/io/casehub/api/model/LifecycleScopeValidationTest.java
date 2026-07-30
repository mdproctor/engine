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

import io.casehub.worker.api.Capability;
import org.junit.jupiter.api.Test;

class LifecycleScopeValidationTest {

  private Capability cap() {
    return Capability.builder().name("test").inputSchema("{}").outputSchema("{}").build();
  }

  @Test
  void binding_defaults_to_binding_participant_transient() {
    Binding b =
        Binding.builder()
            .name("default")
            .capability(cap())
            .on(new ContextChangeTrigger(".x != null"))
            .build();
    assertThat(b.lifecycleScope()).isEqualTo(LifecycleScope.BINDING);
    assertThat(b.participation()).isEqualTo(Participation.PARTICIPANT);
    assertThat(b.executionMode()).isEqualTo(ExecutionMode.TRANSIENT);
  }

  @Test
  void binding_scope_rejects_non_transient_execution_mode() {
    assertThatThrownBy(
            () ->
                Binding.builder()
                    .name("bad")
                    .capability(cap())
                    .on(new ContextChangeTrigger(".x"))
                    .lifecycleScope(LifecycleScope.BINDING)
                    .executionMode(ExecutionMode.PERSISTENT)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BINDING scope requires TRANSIENT");
  }

  @Test
  void companion_requires_compound_or_case_scope() {
    assertThatThrownBy(
            () ->
                Binding.builder()
                    .name("bad")
                    .capability(cap())
                    .on(new ContextChangeTrigger(".x"))
                    .lifecycleScope(LifecycleScope.BINDING)
                    .participation(Participation.COMPANION)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPANION requires COMPOUND or CASE scope");
  }

  @Test
  void scope_activated_trigger_requires_compound_or_case_scope() {
    assertThatThrownBy(
            () ->
                Binding.builder()
                    .name("bad")
                    .capability(cap())
                    .on(new ScopeActivatedTrigger())
                    .lifecycleScope(LifecycleScope.BINDING)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ScopeActivatedTrigger requires COMPOUND or CASE scope");
  }

  @Test
  void case_scope_requires_companion() {
    assertThatThrownBy(
            () ->
                Binding.builder()
                    .name("bad")
                    .capability(cap())
                    .on(new ScopeActivatedTrigger())
                    .lifecycleScope(LifecycleScope.CASE)
                    .participation(Participation.PARTICIPANT)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CASE scope requires COMPANION");
  }

  @Test
  void lifecycle_scope_requires_capability_target() {
    assertThatThrownBy(
            () ->
                Binding.builder()
                    .name("bad")
                    .subCase(SubCase.builder().namespace("ns").name("child").version("1.0").build())
                    .on(new ContextChangeTrigger(".x"))
                    .lifecycleScope(LifecycleScope.COMPOUND)
                    .executionMode(ExecutionMode.PERSISTENT)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires CapabilityTarget");
  }

  @Test
  void compound_scope_with_persistent_and_companion_is_valid() {
    Binding b =
        Binding.builder()
            .name("monitor")
            .capability(cap())
            .on(new ScopeActivatedTrigger())
            .lifecycleScope(LifecycleScope.COMPOUND)
            .participation(Participation.COMPANION)
            .executionMode(ExecutionMode.PERSISTENT)
            .build();
    assertThat(b.lifecycleScope()).isEqualTo(LifecycleScope.COMPOUND);
    assertThat(b.participation()).isEqualTo(Participation.COMPANION);
    assertThat(b.executionMode()).isEqualTo(ExecutionMode.PERSISTENT);
  }

  @Test
  void compound_scope_with_reinvoked_and_participant_is_valid() {
    Binding b =
        Binding.builder()
            .name("analyst")
            .capability(cap())
            .on(new ContextChangeTrigger(".request != null"))
            .lifecycleScope(LifecycleScope.COMPOUND)
            .participation(Participation.PARTICIPANT)
            .executionMode(ExecutionMode.REINVOKED)
            .build();
    assertThat(b.lifecycleScope()).isEqualTo(LifecycleScope.COMPOUND);
    assertThat(b.executionMode()).isEqualTo(ExecutionMode.REINVOKED);
  }

  @Test
  void case_scope_with_companion_is_valid() {
    Binding b =
        Binding.builder()
            .name("case-monitor")
            .capability(cap())
            .on(new ScopeActivatedTrigger())
            .lifecycleScope(LifecycleScope.CASE)
            .participation(Participation.COMPANION)
            .executionMode(ExecutionMode.PERSISTENT)
            .build();
    assertThat(b.lifecycleScope()).isEqualTo(LifecycleScope.CASE);
    assertThat(b.participation()).isEqualTo(Participation.COMPANION);
  }
}
