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
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.casehub.api.model.CaseCompletion;
import io.casehub.worker.api.Worker;
import java.util.Set;

/**
 * Adds {@code unevaluatedProperties: false} to all object type schemas, matching the existing YAML
 * schema's Draft 2020-12 convention. Worker and CaseCompletion are excluded — they use custom
 * additionalProperties.
 */
public class UnevaluatedPropertiesModule implements Module {

  private static final Set<Class<?>> EXCLUDED = Set.of(Worker.class, CaseCompletion.class);

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withTypeAttributeOverride(
            (schema, scope, context) -> {
              if (EXCLUDED.contains(scope.getType().getErasedType())) {
                return;
              }
              if (schema.has("type") && "object".equals(schema.get("type").asText())) {
                if (!schema.has("additionalProperties")
                    || !schema.get("additionalProperties").asBoolean()) {
                  schema.remove("additionalProperties");
                  ((ObjectNode) schema).put("unevaluatedProperties", false);
                }
              }
            });
  }
}
