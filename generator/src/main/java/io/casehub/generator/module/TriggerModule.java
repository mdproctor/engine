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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.casehub.api.model.Trigger;

/**
 * Replaces the default Trigger schema (empty marker interface) with the named-property oneOf
 * pattern: exactly one of contextChange, cloudEvent, schedule, scopeActivated.
 */
public class TriggerModule implements Module {

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withCustomDefinitionProvider(
            (type, context) -> {
              if (type.getErasedType() == Trigger.class) {
                ObjectNode schema = context.getGeneratorConfig().createObjectNode();
                schema.put("type", "object");
                schema.put(
                    "description",
                    "Defines what the Worker observes. Exactly one of:"
                        + " contextChange, cloudEvent, schedule, scopeActivated.");
                schema.put("unevaluatedProperties", false);

                ArrayNode oneOf = schema.putArray("oneOf");
                oneOf.addObject().putArray("required").add("contextChange");
                oneOf.addObject().putArray("required").add("cloudEvent");
                oneOf.addObject().putArray("required").add("schedule");
                oneOf.addObject().putArray("required").add("scopeActivated");

                ObjectNode properties = schema.putObject("properties");
                properties.putObject("contextChange").put("$ref", "#/$defs/ContextChangeTrigger");
                properties.putObject("cloudEvent").put("$ref", "#/$defs/CloudEventTrigger");
                properties.putObject("schedule").put("$ref", "#/$defs/ScheduleTrigger");
                properties.putObject("scopeActivated").put("$ref", "#/$defs/ScopeActivatedTrigger");

                return new CustomDefinition(schema);
              }
              return null;
            });
  }
}
