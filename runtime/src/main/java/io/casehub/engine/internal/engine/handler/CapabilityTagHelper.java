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
package io.casehub.engine.internal.engine.handler;

import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;

final class CapabilityTagHelper {

  private CapabilityTagHelper() {}

  static String extractCapabilityTag(CaseDefinition definition, String bindingName) {
    if (bindingName == null || definition == null || definition.getBindings() == null) {
      return null;
    }
    return definition.getBindings().stream()
        .filter(b -> b.getName().equals(bindingName))
        .filter(b -> b.target() instanceof CapabilityTarget)
        .map(b -> ((CapabilityTarget) b.target()).capability().name())
        .findFirst()
        .orElse(null);
  }
}
