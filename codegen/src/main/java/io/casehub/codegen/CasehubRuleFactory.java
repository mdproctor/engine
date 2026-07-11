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
package io.casehub.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.codemodel.JClassContainer;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JType;
import org.jsonschema2pojo.Schema;
import org.jsonschema2pojo.rules.Rule;
import org.jsonschema2pojo.rules.RuleFactory;

/**
 * Custom jsonschema2pojo rule factory for the CaseHub schema.
 *
 * <p>Extends the default code generation with two overrides:
 *
 * <ul>
 *   <li><b>Worker type reuse:</b> References to the {@code Worker} schema resolve to the
 *       hand-written {@code io.casehub.model.Worker} class instead of generating a new one.
 *   <li><b>Typed additionalProperties:</b> When a schema declares {@code additionalProperties} with
 *       a {@code $ref} to a named type (e.g. {@code GoalExpression}), the generated class gets
 *       {@code Map<String, GoalExpression>} instead of {@code Map<String, Object>}.
 * </ul>
 *
 * <h3>Why typed additionalProperties needs special handling</h3>
 *
 * <p>The global config sets {@code isIncludeAdditionalProperties = false} to prevent every
 * generated class from acquiring a {@code Map<String, Object>} field — most schema types use {@code
 * unevaluatedProperties: false} (a JSON Schema 2020-12 keyword that jsonschema2pojo does not
 * enforce) and would otherwise gain an unwanted catch-all map.
 *
 * <p>However, {@code CaseCompletion} legitimately uses {@code additionalProperties} with a typed
 * {@code $ref} to {@code GoalExpression}. The default {@link
 * org.jsonschema2pojo.rules.AdditionalPropertiesRule} checks the global config flag first and skips
 * generation entirely when it is {@code false}, regardless of whether the schema provides a typed
 * reference. This override bypasses the flag specifically when the {@code additionalProperties}
 * node is an object schema (i.e. carries a {@code $ref} or other type constraints), while still
 * delegating to the default rule for untyped ({@code true}) or absent additional properties.
 *
 * <p>The result for {@code CaseCompletion}:
 *
 * <pre>{@code
 * // Generated field and accessors:
 * private Map<String, GoalExpression> additionalProperties = new LinkedHashMap<>();
 *
 * @JsonAnyGetter
 * public Map<String, GoalExpression> getAdditionalProperties() { ... }
 *
 * @JsonAnySetter
 * public void setAdditionalProperty(String name, GoalExpression value) { ... }
 * }</pre>
 *
 * <p>Consumers get compile-time type safety and direct access to {@code GoalExpression.getAllOf()}
 * / {@code GoalExpression.getAnyOf()} without casting. See {@code
 * CaseCompletionDeserializationTest} in the schema module for comprehensive usage examples.
 *
 * @see org.jsonschema2pojo.rules.AdditionalPropertiesRule
 * @see io.casehub.model.CaseCompletion
 * @see io.casehub.model.GoalExpression
 */
public class CasehubRuleFactory extends RuleFactory {

  private static final String WORKER_FQCN = "io.casehub.model.Worker";

  /**
   * Overrides schema resolution so that any reference to the {@code Worker} schema type reuses the
   * hand-written {@code io.casehub.model.Worker} class rather than generating a duplicate.
   */
  @Override
  public Rule<JClassContainer, JType> getSchemaRule() {
    Rule<JClassContainer, JType> defaultRule = super.getSchemaRule();
    return (nodeName, node, parent, jPackage, schema) -> {
      if (matches(nodeName, node, "Worker")) {
        return jPackage.owner().directClass(WORKER_FQCN);
      }
      return defaultRule.apply(nodeName, node, parent, jPackage, schema);
    };
  }

  /**
   * Overrides additional properties handling to support typed {@code $ref} schemas even when the
   * global {@code isIncludeAdditionalProperties} flag is {@code false}.
   *
   * <p>When the {@code additionalProperties} node is an object schema (e.g. contains a {@code
   * $ref}), this rule generates a typed map field directly — bypassing the global flag check. For
   * all other cases ({@code additionalProperties: true}, absent, or {@code false}), delegates to
   * the default rule which respects the global config.
   *
   * <p>This selective approach avoids adding {@code Map<String, Object>} to the ~20 schema types
   * that use {@code unevaluatedProperties: false} (which jsonschema2pojo does not enforce), while
   * still generating typed maps where the schema explicitly declares them.
   */
  @Override
  public Rule<JDefinedClass, JDefinedClass> getAdditionalPropertiesRule() {
    Rule<JDefinedClass, JDefinedClass> defaultRule = super.getAdditionalPropertiesRule();
    return (nodeName, node, parent, jclass, schema) -> {
      if (isTypedAdditionalProperties(node)) {
        return applyTypedAdditionalProperties(nodeName, node, parent, jclass, schema);
      }
      return defaultRule.apply(nodeName, node, parent, jclass, schema);
    };
  }

  /**
   * Returns {@code true} when the additionalProperties node is a JSON object (a schema definition,
   * not just a boolean {@code true}). An object node indicates a typed reference such as {@code
   * additionalProperties: { $ref: "#/$defs/GoalExpression" }}.
   */
  private static boolean isTypedAdditionalProperties(JsonNode node) {
    return node != null && node.isObject() && node.size() > 0;
  }

  /**
   * Generates a typed additional properties map field, getter, and setter for the given class.
   *
   * <p>Mirrors the logic of {@link org.jsonschema2pojo.rules.AdditionalPropertiesRule} but skips
   * the {@code isIncludeAdditionalProperties} config check. The generated field uses {@link
   * java.util.LinkedHashMap} to preserve YAML document order — essential for {@code CaseCompletion}
   * where insertion order determines evaluation priority.
   *
   * <p>The generated code is annotated with {@code @JsonAnyGetter} (getter) and
   * {@code @JsonAnySetter} (setter) so that Jackson maps unknown JSON properties into the typed map
   * during deserialization and flattens them back during serialization.
   */
  private JDefinedClass applyTypedAdditionalProperties(
      String nodeName, JsonNode node, JsonNode parent, JDefinedClass jclass, Schema schema) {

    String pathToAdditionalProperties;
    if (schema.getId() == null || schema.getId().getFragment() == null) {
      pathToAdditionalProperties = "#/additionalProperties";
    } else {
      pathToAdditionalProperties = "#" + schema.getId().getFragment() + "/additionalProperties";
    }
    Schema additionalPropertiesSchema =
        getSchemaStore()
            .create(
                schema,
                pathToAdditionalProperties,
                getGenerationConfig().getRefFragmentPathDelimiters());
    JType propertyType =
        getSchemaRule()
            .apply(nodeName + "Property", node, parent, jclass, additionalPropertiesSchema);
    additionalPropertiesSchema.setJavaTypeIfEmpty(propertyType);

    com.sun.codemodel.JClass propertiesMapType = jclass.owner().ref(java.util.Map.class);
    propertiesMapType =
        propertiesMapType.narrow(jclass.owner().ref(String.class), propertyType.boxify());

    com.sun.codemodel.JClass propertiesMapImplType =
        jclass.owner().ref(java.util.LinkedHashMap.class);
    propertiesMapImplType =
        propertiesMapImplType.narrow(jclass.owner().ref(String.class), propertyType.boxify());

    com.sun.codemodel.JFieldVar field =
        jclass.field(com.sun.codemodel.JMod.PRIVATE, propertiesMapType, "additionalProperties");
    getAnnotator().additionalPropertiesField(field, jclass, "additionalProperties");
    field.init(com.sun.codemodel.JExpr._new(propertiesMapImplType));

    com.sun.codemodel.JMethod getter =
        jclass.method(com.sun.codemodel.JMod.PUBLIC, field.type(), "getAdditionalProperties");
    getAnnotator().anyGetter(getter, jclass);
    getter.body()._return(com.sun.codemodel.JExpr._this().ref(field));

    com.sun.codemodel.JMethod setter =
        jclass.method(com.sun.codemodel.JMod.PUBLIC, void.class, "setAdditionalProperty");
    getAnnotator().anySetter(setter, jclass);
    com.sun.codemodel.JVar nameParam = setter.param(String.class, "name");
    com.sun.codemodel.JVar valueParam = setter.param(propertyType, "value");
    setter
        .body()
        .invoke(com.sun.codemodel.JExpr._this().ref(field), "put")
        .arg(nameParam)
        .arg(valueParam);

    return jclass;
  }

  private static boolean matches(String nodeName, JsonNode node, String typeName) {
    if (typeName.equals(nodeName)) {
      return true;
    }
    if (node.has("$ref")) {
      String ref = node.get("$ref").asText();
      return ref.endsWith("/" + typeName) || ref.equals("#/$defs/" + typeName);
    }
    return false;
  }
}
