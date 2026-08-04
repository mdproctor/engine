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
package io.casehub.ledger.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;
import io.quarkus.runtime.StartupEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrustRoutingPreferenceRegistrarTest {

  @Test
  void registers_all_five_trust_routing_keys() {
    var registry = new RecordingRegistry();
    var registrar = new TrustRoutingPreferenceRegistrar();
    registrar.registry = registry;

    registrar.onStart(new StartupEvent());

    assertThat(registry.registered).hasSize(5);
    var names = registry.registered.stream().map(PreferenceSchemaDescriptor::name).toList();
    assertThat(names)
        .containsExactlyInAnyOrder(
            "threshold", "minimum-observations", "borderline-margin", "blend-factor", "cbr-weight");
  }

  @Test
  void all_descriptors_have_correct_namespace() {
    var registry = new RecordingRegistry();
    var registrar = new TrustRoutingPreferenceRegistrar();
    registrar.registry = registry;

    registrar.onStart(new StartupEvent());

    for (var d : registry.registered) {
      assertThat(d.namespace()).isEqualTo("casehub.engine.trust-routing");
    }
  }

  @Test
  void threshold_descriptor_has_correct_shape() {
    var registry = new RecordingRegistry();
    var registrar = new TrustRoutingPreferenceRegistrar();
    registrar.registry = registry;

    registrar.onStart(new StartupEvent());

    var threshold =
        registry.registered.stream()
            .filter(d -> d.name().equals("threshold"))
            .findFirst()
            .orElseThrow();
    assertThat(threshold.type()).isEqualTo("number");
    assertThat(threshold.defaultValue()).isEqualTo("0.0");
    assertThat(threshold.constraints()).containsEntry("min", 0.0);
    assertThat(threshold.constraints()).containsEntry("max", 1.0);
    assertThat(threshold.label()).isNotBlank();
    assertThat(threshold.description()).isNotBlank();
  }

  @Test
  void minimum_observations_is_integer_type() {
    var registry = new RecordingRegistry();
    var registrar = new TrustRoutingPreferenceRegistrar();
    registrar.registry = registry;

    registrar.onStart(new StartupEvent());

    var minObs =
        registry.registered.stream()
            .filter(d -> d.name().equals("minimum-observations"))
            .findFirst()
            .orElseThrow();
    assertThat(minObs.type()).isEqualTo("integer");
  }

  private static final class RecordingRegistry implements PreferenceSchemaRegistry {
    final List<PreferenceSchemaDescriptor> registered = new ArrayList<>();

    @Override
    public void register(PreferenceSchemaDescriptor descriptor) {
      registered.add(descriptor);
    }

    @Override
    public Optional<PreferenceSchemaDescriptor> resolve(String qualifiedName) {
      return registered.stream().filter(d -> d.qualifiedName().equals(qualifiedName)).findFirst();
    }

    @Override
    public Set<PreferenceSchemaDescriptor> discover() {
      return Set.copyOf(registered);
    }
  }
}
