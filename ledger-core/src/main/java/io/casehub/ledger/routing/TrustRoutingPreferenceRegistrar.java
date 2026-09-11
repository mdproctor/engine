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

import io.casehub.api.spi.routing.TrustRoutingPolicyKeys;
import io.casehub.platform.api.preferences.PreferenceConstraintKeys;
import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class TrustRoutingPreferenceRegistrar {

  private static final TrustRoutingPolicyKeys KEYS =
      TrustRoutingPolicyKeys.create("casehub.engine.trust-routing");

  @Inject PreferenceSchemaRegistry registry;

  void onStart(@Observes StartupEvent event) {
    registry.register(
        PreferenceSchemaDescriptor.of(KEYS.threshold())
            .type("number")
            .label("Trust threshold")
            .description("Minimum CAPABILITY trust score for agent selection (Phase 2 entry)")
            .constraints(
                Map.of(PreferenceConstraintKeys.MIN, 0.0, PreferenceConstraintKeys.MAX, 1.0))
            .build());

    registry.register(
        PreferenceSchemaDescriptor.of(KEYS.minimumObservations())
            .type("integer")
            .label("Minimum observations")
            .description("Decision count below which routing falls to Phase 0/1 (availability)")
            .constraints(Map.of(PreferenceConstraintKeys.MIN, 0))
            .build());

    registry.register(
        PreferenceSchemaDescriptor.of(KEYS.borderlineMargin())
            .type("number")
            .label("Borderline margin")
            .description(
                "Score margin around the threshold — candidates within this band are excluded")
            .constraints(
                Map.of(PreferenceConstraintKeys.MIN, 0.0, PreferenceConstraintKeys.MAX, 1.0))
            .build());

    registry.register(
        PreferenceSchemaDescriptor.of(KEYS.blendFactor())
            .type("number")
            .label("Trust/workload blend factor")
            .description(
                "Weight of trust score vs workload efficiency (0.0 = pure workload, 1.0 = pure trust)")
            .constraints(
                Map.of(PreferenceConstraintKeys.MIN, 0.0, PreferenceConstraintKeys.MAX, 1.0))
            .build());

    registry.register(
        PreferenceSchemaDescriptor.of(KEYS.cbrWeight())
            .type("number")
            .label("CBR weight")
            .description("Weight of case-based reasoning score in trust routing blend")
            .constraints(
                Map.of(PreferenceConstraintKeys.MIN, 0.0, PreferenceConstraintKeys.MAX, 1.0))
            .build());
  }
}
