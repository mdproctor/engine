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
package io.casehub.api.model.converter.deser;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseCompletion;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseDefinitionSpec;
import io.casehub.api.model.Goal;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class CaseDefinitionDeserializer extends StdDeserializer<CaseDefinition> {

  public CaseDefinitionDeserializer() {
    super(CaseDefinition.class);
  }

  @Override
  public CaseDefinition deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    ObjectCodec codec = p.getCodec();
    JsonNode root = p.readValueAsTree();
    if (root == null || root.isNull()) {
      return null;
    }

    String namespace = textOrNull(root, "namespace");
    String name = textOrNull(root, "name");
    String version = textOrNull(root, "version");
    CaseDefinition def = new CaseDefinition(namespace, name, version);

    def.setDsl(textOrNull(root, "dsl"));
    def.setTitle(textOrNull(root, "title"));
    def.setSummary(textOrNull(root, "summary"));

    String expressionLang = textOrNull(root, "expressionLang");
    if (expressionLang != null) {
      def.setExpressionLang(expressionLang);
      ctxt.setAttribute(ExpressionEvaluatorDeserializer.EXPRESSION_LANG_KEY, expressionLang);
    }

    if (root.has("context") && root.get("context").has("storeFactory")) {
      def.setContextStoreFactory(root.get("context").get("storeFactory").asText());
    }
    if (root.has("contextType")) {
      def.setContextType(root.get("contextType").asText());
    }

    if (root.has("semanticData") && root.get("semanticData").isObject()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> sd =
          ((ObjectMapper) codec).convertValue(root.get("semanticData"), Map.class);
      def.setSemanticData(sd);
    }

    JsonNode specNode = root.has("spec") ? root.get("spec") : root;

    Map<String, CapabilityTarget> capTargetMap = parseCapabilities(specNode, def, codec, ctxt);
    ctxt.setAttribute(BindingDeserializer.CAPABILITY_TARGET_MAP_KEY, capTargetMap);

    parseArrayInto(specNode, "workers", Worker.class, def.getWorkers()::add, codec, ctxt);
    parseArrayInto(specNode, "bindings", Binding.class, def.getBindings()::add, codec, ctxt);

    if (specNode.has("goals") && specNode.get("goals").isArray()) {
      for (JsonNode gn : specNode.get("goals")) {
        def.getGoals().add(deserializeGoal(gn, codec, ctxt));
      }
    }

    if (specNode.has("milestones") && specNode.get("milestones").isArray()) {
      for (JsonNode mn : specNode.get("milestones")) {
        def.getMilestones().add(deserializeMilestone(mn, codec, ctxt));
      }
    }

    if (specNode.has("completion")) {
      def.setCompletion(readValue(specNode.get("completion"), CaseCompletion.class, codec, ctxt));
    }

    CaseDefinitionSpec spec = def.getSpec();
    setTextIfPresent(specNode, "planningStrategy", spec::setPlanningStrategy);
    setTextIfPresent(specNode, "agentRouting", spec::setAgentRouting);
    setTextIfPresent(specNode, "implementationRouting", spec::setImplementationRouting);
    setTextIfPresent(specNode, "humanTaskRouting", spec::setHumanTaskRouting);
    setTextIfPresent(specNode, "candidateMatching", spec::setCandidateMatching);
    setTextIfPresent(specNode, "decompositionStrategy", spec::setDecompositionStrategy);
    if (specNode.has("maxDecompositionDepth")) {
      spec.setMaxDecompositionDepth(specNode.get("maxDecompositionDepth").asInt());
    }
    if (specNode.has("maxAdaptations")) {
      spec.setMaxAdaptations(specNode.get("maxAdaptations").asInt());
    }

    return def;
  }

  private Map<String, CapabilityTarget> parseCapabilities(
      JsonNode specNode, CaseDefinition def, ObjectCodec codec, DeserializationContext ctxt) {
    Map<String, CapabilityTarget> capTargetMap = new LinkedHashMap<>();
    if (!specNode.has("capabilities") || !specNode.get("capabilities").isArray()) {
      return capTargetMap;
    }
    for (JsonNode capNode : specNode.get("capabilities")) {
      String capName = textOrNull(capNode, "name");
      String inputProj =
          capNode.has("inputProjection") ? capNode.get("inputProjection").asText() : ".";
      String outputProj =
          capNode.has("outputProjection") ? capNode.get("outputProjection").asText() : ".";
      String desc = textOrNull(capNode, "description");

      Capability cap =
          Capability.builder()
              .name(capName)
              .inputSchema(inputProj)
              .outputSchema(outputProj)
              .description(desc)
              .build();
      def.getCapabilities().add(cap);
      capTargetMap.put(
          capName,
          new CapabilityTarget(
              cap, new JQExpressionEvaluator(inputProj), new JQExpressionEvaluator(outputProj)));
    }
    return capTargetMap;
  }

  private Goal deserializeGoal(JsonNode node, ObjectCodec codec, DeserializationContext ctxt)
      throws IOException {
    String goalName = textOrNull(node, "name");
    ExpressionEvaluator condition = null;
    if (node.has("condition")) {
      condition = readValue(node.get("condition"), ExpressionEvaluator.class, codec, ctxt);
    }
    String kind = textOrNull(node, "kind");
    Goal goal = new Goal(goalName, condition, kind);
    if (node.has("description")) {
      goal.setDescription(node.get("description").asText());
    }
    return goal;
  }

  private Milestone deserializeMilestone(
      JsonNode node, ObjectCodec codec, DeserializationContext ctxt) throws IOException {
    Milestone.Builder b = Milestone.builder();
    if (node.has("name")) b.name(node.get("name").asText());
    if (node.has("entryCriteria")) {
      b.entryCriteria(readValue(node.get("entryCriteria"), ExpressionEvaluator.class, codec, ctxt));
    }
    if (node.has("completionCriteria")) {
      b.completionCriteria(
          readValue(node.get("completionCriteria"), ExpressionEvaluator.class, codec, ctxt));
    }
    if (node.has("condition")) {
      b.entryCriteria(readValue(node.get("condition"), ExpressionEvaluator.class, codec, ctxt));
    }
    if (node.has("slaDuration")) {
      b.slaDuration(java.time.Duration.parse(node.get("slaDuration").asText()));
    }
    if (node.has("description")) {
      b.description(node.get("description").asText());
    }
    return b.build();
  }

  private <T> void parseArrayInto(
      JsonNode parent,
      String field,
      Class<T> type,
      Consumer<T> adder,
      ObjectCodec codec,
      DeserializationContext ctxt)
      throws IOException {
    if (!parent.has(field) || !parent.get(field).isArray()) {
      return;
    }
    for (JsonNode elem : parent.get(field)) {
      adder.accept(readValue(elem, type, codec, ctxt));
    }
  }

  private <T> T readValue(
      JsonNode node, Class<T> type, ObjectCodec codec, DeserializationContext ctxt)
      throws IOException {
    JsonParser nested = node.traverse(codec);
    nested.nextToken();
    return ctxt.readValue(nested, type);
  }

  private static String textOrNull(JsonNode node, String field) {
    return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
  }

  private static void setTextIfPresent(JsonNode node, String field, Consumer<String> setter) {
    if (node.has(field) && !node.get(field).isNull()) {
      setter.accept(node.get(field).asText());
    }
  }

  @Override
  public CaseDefinition getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
