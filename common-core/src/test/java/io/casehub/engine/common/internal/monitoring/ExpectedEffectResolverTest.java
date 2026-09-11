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
package io.casehub.engine.common.internal.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.monitoring.ExpectedEffects;
import io.casehub.worker.api.Capability;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExpectedEffectResolverTest {

  private final ExpectedEffectResolver resolver = new ExpectedEffectResolver();

  @Test
  void resolves_goap_action_effects_by_capability_name() {
    Capability cap = Capability.of("analyse", ".", ".");
    Binding binding =
        Binding.builder()
            .name("analyse-binding")
            .capability(cap)
            .on(new ContextChangeTrigger(".input != null"))
            .build();
    GoapAction action =
        new GoapAction("analyse", Map.of("input", true), Map.of("analysed", true), 1.0);
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1")
            .capabilities(cap)
            .bindings(binding)
            .goapActions(List.of(action))
            .build();

    ExpectedEffects result = resolver.resolve(def, "analyse-binding");
    assertFalse(result.isEmpty());
    assertEquals(ExpectedEffects.EffectSource.GOAP, result.source());
    assertEquals(Map.of("analysed", true), result.effects());
  }

  @Test
  void falls_back_to_produced_keys() {
    Capability cap = Capability.of("process", ".", ".");
    Binding binding =
        Binding.builder()
            .name("process-binding")
            .capability(cap)
            .on(new ContextChangeTrigger(".input != null"))
            .producedKeys(Set.of("result", "status"))
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1")
            .capabilities(cap)
            .bindings(binding)
            .build();

    ExpectedEffects result = resolver.resolve(def, "process-binding");
    assertFalse(result.isEmpty());
    assertEquals(ExpectedEffects.EffectSource.PRODUCED_KEYS, result.source());
    assertTrue(result.effects().get("result"));
    assertTrue(result.effects().get("status"));
  }

  @Test
  void goap_takes_precedence_over_produced_keys() {
    Capability cap = Capability.of("analyse", ".", ".");
    Binding binding =
        Binding.builder()
            .name("analyse-binding")
            .capability(cap)
            .on(new ContextChangeTrigger(".input != null"))
            .producedKeys(Set.of("result"))
            .build();
    GoapAction action =
        new GoapAction("analyse", Map.of(), Map.of("analysed", true, "verified", false), 1.0);
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1")
            .capabilities(cap)
            .bindings(binding)
            .goapActions(List.of(action))
            .build();

    ExpectedEffects result = resolver.resolve(def, "analyse-binding");
    assertEquals(ExpectedEffects.EffectSource.GOAP, result.source());
    assertEquals(2, result.effects().size());
  }

  @Test
  void returns_empty_for_unknown_binding() {
    CaseDefinition def = CaseDefinition.builder().namespace("ns").name("test").version("1").build();
    ExpectedEffects result = resolver.resolve(def, "nonexistent");
    assertTrue(result.isEmpty());
  }

  @Test
  void returns_empty_when_no_effects_and_no_produced_keys() {
    Capability cap = Capability.of("process", ".", ".");
    Binding binding =
        Binding.builder()
            .name("process-binding")
            .capability(cap)
            .on(new ContextChangeTrigger(".input != null"))
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1")
            .capabilities(cap)
            .bindings(binding)
            .build();

    ExpectedEffects result = resolver.resolve(def, "process-binding");
    assertTrue(result.isEmpty());
  }
}
