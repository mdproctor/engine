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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.casehub.worker.api.Worker;

/**
 * Replaces the default Worker schema with the YAML-convention structure. Worker is an extension
 * point — plugin function types (agent, do, mcp, a2a, react) are declared via additionalProperties.
 */
public class WorkerSchemaModule implements Module {

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withCustomDefinitionProvider(
            (type, context) -> {
              if (type.getErasedType() == Worker.class) {
                ObjectNode schema =
                    context
                        .getGeneratorConfig()
                        .createObjectNode()
                        .put("type", "object")
                        .put("additionalProperties", true);

                ObjectNode properties = schema.putObject("properties");

                properties.putObject("name").put("type", "string").put("minLength", 1);
                properties.putObject("description").put("type", "string");

                ObjectNode capabilities = properties.putObject("capabilities");
                capabilities.put("type", "array").put("minItems", 1);
                capabilities.putObject("items").put("type", "string");

                properties.putObject("executionPolicy").put("$ref", "#/$defs/ExecutionPolicy");

                ObjectNode sequence = properties.putObject("sequence");
                sequence.put("type", "array");
                sequence.putObject("items").put("type", "string");

                properties.putObject("contextType").put("type", "string");
                properties.putObject("outputType").put("type", "string");

                ObjectNode costProp = properties.putObject("cost");
                costProp.put("type", "number");
                costProp.put("minimum", 0);

                ObjectNode effectProp = properties.putObject("effect");
                effectProp.put("type", "object");
                effectProp.putObject("additionalProperties").put("type", "boolean");

                ObjectNode softDep = properties.putObject("softDependency");
                softDep.put("type", "array");
                softDep.putObject("items").put("type", "string");

                schema.putArray("required").add("name").add("capabilities");

                return new CustomDefinition(schema);
              }
              return null;
            });
  }
}
