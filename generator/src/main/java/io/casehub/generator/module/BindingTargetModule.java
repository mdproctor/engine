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
package io.casehub.generator.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.casehub.api.model.Binding;

/**
 * Transforms the Binding schema to use flat named-property oneOf for target types instead of a
 * single {@code target} field with a sealed-interface $ref. Replaces the {@code target} property
 * with {@code capability} (string), {@code subCase} ($ref), {@code humanTask} ($ref), and {@code
 * signal} (object). {@code ExtensionTarget} is excluded (engine-internal).
 */
public class BindingTargetModule implements Module {

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withTypeAttributeOverride(
            (schema, scope, context) -> {
              if (scope.getType().getErasedType() != Binding.class) {
                return;
              }

              JsonNode propertiesNode = schema.get("properties");
              if (propertiesNode == null || !propertiesNode.isObject()) {
                return;
              }

              ObjectNode properties = (ObjectNode) propertiesNode;
              properties.remove("target");

              properties.putObject("capability").put("type", "string");
              properties.putObject("subCase").put("$ref", "#/$defs/SubCase");
              properties.putObject("humanTask").put("$ref", "#/$defs/HumanTask");
              ObjectNode signal = properties.putObject("signal");
              signal.put("type", "object");
              signal.put("additionalProperties", true);
              signal.put(
                  "description",
                  "Context signal payload. Written to the case context"
                      + " when the binding fires. No worker dispatch.");

              ArrayNode oneOf = schema.putArray("oneOf");
              oneOf.addObject().putArray("required").add("capability");
              oneOf.addObject().putArray("required").add("subCase");
              oneOf.addObject().putArray("required").add("humanTask");
              oneOf.addObject().putArray("required").add("signal");
            });
  }
}
