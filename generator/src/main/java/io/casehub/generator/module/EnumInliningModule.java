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

/**
 * Inlines all enum types as {@code {type: string, enum: [values]}} instead of generating $defs with
 * $ref. The hand-written CaseDefinition.yaml uses inline enum values — this module reproduces that
 * convention.
 */
public class EnumInliningModule implements Module {

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withCustomDefinitionProvider(
            (type, context) -> {
              Class<?> erasedType = type.getErasedType();
              if (!erasedType.isEnum()) {
                return null;
              }
              ObjectNode schema = context.getGeneratorConfig().createObjectNode();
              schema.put("type", "string");
              ArrayNode enumValues = schema.putArray("enum");
              for (Object constant : erasedType.getEnumConstants()) {
                enumValues.add(constant.toString());
              }
              return new CustomDefinition(schema, true);
            });
  }
}
