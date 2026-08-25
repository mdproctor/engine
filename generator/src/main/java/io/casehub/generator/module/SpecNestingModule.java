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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import io.casehub.api.model.CaseDefinition;
import java.util.Set;

/**
 * Post-processes the CaseDefinition schema to nest spec-level properties under a {@code spec}
 * object, matching the existing YAML schema convention.
 *
 * <p>The YAML schema has three semantic groups:
 *
 * <ul>
 *   <li>Identity (root): dsl, namespace, name, version, title, summary
 *   <li>Configuration (root): context, episodic, signals, labelRules, inboundMappings, layers, use,
 *       semanticData, types, labels, expressionLang, contextType
 *   <li>Specification (under spec): capabilities, workers, bindings, goals, milestones, completion,
 *       strategy IDs, cbr, channels, authorization, routing weights, etc.
 * </ul>
 */
public class SpecNestingModule implements Module {

  private static final Set<String> ROOT_PROPERTIES =
      Set.of(
          "dsl",
          "namespace",
          "name",
          "version",
          "title",
          "summary",
          "expressionLang",
          "contextType",
          "use",
          "semanticData",
          "episodicMemoryConfig",
          "layerNames",
          "contextStoreFactory",
          "signals",
          "labelRules",
          "inboundMappings",
          "types",
          "labels",
          "context",
          "episodic",
          "layers");

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withTypeAttributeOverride(
            (schema, scope, context) -> {
              if (scope.getType().getErasedType() != CaseDefinition.class) {
                return;
              }

              JsonNode propertiesNode = schema.get("properties");
              if (propertiesNode == null || !propertiesNode.isObject()) {
                return;
              }

              ObjectNode rootProperties = (ObjectNode) propertiesNode;
              ObjectNode specSchema = context.getGeneratorConfig().createObjectNode();
              specSchema.put("type", "object");
              specSchema.put("unevaluatedProperties", true);
              specSchema.put(
                  "description",
                  "Case definition specification. unevaluatedProperties is true"
                      + " because the spec is an extension point.");
              ObjectNode specProperties = specSchema.putObject("properties");

              var fieldNames = new java.util.ArrayList<String>();
              rootProperties.fieldNames().forEachRemaining(fieldNames::add);

              for (String fieldName : fieldNames) {
                if (!ROOT_PROPERTIES.contains(fieldName)) {
                  specProperties.set(fieldName, rootProperties.get(fieldName));
                  rootProperties.remove(fieldName);
                }
              }

              rootProperties.set("spec", specSchema);
            });
  }
}
