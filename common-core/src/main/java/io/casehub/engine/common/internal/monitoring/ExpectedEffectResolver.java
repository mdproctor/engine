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

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.monitoring.ExpectedEffects;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ExpectedEffectResolver {

  public ExpectedEffects resolve(CaseDefinition definition, String bindingName) {
    Binding binding = findBindingByName(definition, bindingName);
    if (binding == null) {
      return new ExpectedEffects(Map.of(), ExpectedEffects.EffectSource.GOAP);
    }

    if (binding.target() instanceof CapabilityTarget ct) {
      String capabilityName = ct.capability().name();
      if (definition.getGoapActions() != null) {
        for (GoapAction action : definition.getGoapActions()) {
          if (action.name().equals(capabilityName) && !action.effects().isEmpty()) {
            return new ExpectedEffects(action.effects(), ExpectedEffects.EffectSource.GOAP);
          }
        }
      }
    }

    Set<String> producedKeys = binding.getProducedKeys();
    if (producedKeys != null && !producedKeys.isEmpty()) {
      Map<String, Boolean> effects = new HashMap<>();
      producedKeys.forEach(key -> effects.put(key, true));
      return new ExpectedEffects(effects, ExpectedEffects.EffectSource.PRODUCED_KEYS);
    }

    return new ExpectedEffects(Map.of(), ExpectedEffects.EffectSource.GOAP);
  }

  private Binding findBindingByName(CaseDefinition definition, String bindingName) {
    if (definition.getBindings() == null || bindingName == null) {
      return null;
    }
    for (Binding binding : definition.getBindings()) {
      if (bindingName.equals(binding.getName())) {
        return binding;
      }
    }
    return null;
  }
}
