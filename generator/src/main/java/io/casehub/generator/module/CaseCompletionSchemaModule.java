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
import io.casehub.api.model.CaseCompletion;

/**
 * Replaces the default CaseCompletion schema (empty marker interface) with the YAML convention:
 * doneWhen predicate + additionalProperties typed as GoalExpression.
 */
public class CaseCompletionSchemaModule implements Module {

  @Override
  public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
    builder
        .forTypesInGeneral()
        .withCustomDefinitionProvider(
            (type, context) -> {
              if (type.getErasedType() == CaseCompletion.class) {
                ObjectNode schema =
                    context.getGeneratorConfig().createObjectNode().put("type", "object");

                schema.put(
                    "description",
                    "Maps goal kinds to goal expressions. Document order determines"
                        + " evaluation priority — first satisfied expression wins.");

                ObjectNode properties = schema.putObject("properties");
                ObjectNode doneWhen = properties.putObject("doneWhen");
                doneWhen.put("$ref", "#/$defs/ExpressionOrOverride");
                doneWhen.put(
                    "description",
                    "Optional predicate over CaseContext — string or {lang: expr}" + " override.");

                ObjectNode additionalProps = schema.putObject("additionalProperties");
                additionalProps.put("$ref", "#/$defs/GoalExpression");

                return new CustomDefinition(schema);
              }
              return null;
            });
  }
}
