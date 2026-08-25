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
import io.casehub.platform.api.expression.ExpressionEvaluator;

/**
 * Maps {@link ExpressionEvaluator} to the ExpressionOrOverride schema pattern: either a plain
 * string (uses the definition-level expressionLang) or a single-property object for per-expression
 * language override ({@code { jq: ".expr" }} or {@code { mvel: "expr" }}).
 */
public class ExpressionEvaluatorModule implements Module {

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withCustomDefinitionProvider(
            (type, context) -> {
              if (ExpressionEvaluator.class.isAssignableFrom(type.getErasedType())) {
                return new CustomDefinition(
                    buildExpressionOrOverrideSchema(
                        context.getGeneratorConfig().createObjectNode()));
              }
              return null;
            });
  }

  static ObjectNode buildExpressionOrOverrideSchema(ObjectNode node) {
    node.put(
        "description",
        "Expression string or per-expression language override map."
            + " Plain string uses the definition-level expressionLang."
            + " Map syntax overrides: { jq: \".expr\" } or { mvel: \"expr\" }.");

    ArrayNode oneOf = node.putArray("oneOf");
    oneOf.addObject().put("type", "string");

    ObjectNode mapVariant = oneOf.addObject();
    mapVariant.put("type", "object");
    mapVariant.put("minProperties", 1);
    mapVariant.put("maxProperties", 1);
    mapVariant.putObject("additionalProperties").put("type", "string");

    return node;
  }
}
