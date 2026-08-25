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
package io.casehub.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-processes the victools-generated schema to match the hand-written CaseDefinition.yaml
 * structure. Handles $def renaming, removal of Java-internal types, addition of YAML-only types,
 * and root-level metadata.
 */
final class SchemaPostProcessor {

  private static final Set<String> UNWANTED_DEFS =
      Set.of(
          "Participation",
          "LifecycleScope",
          "ExecutionMode",
          "OutcomeAction",
          "SideEffectClassification",
          "ReplanHint",
          "CbrRetrievalTiming",
          "RecoveryLevel",
          "OutcomeType",
          "AclAction",
          "SlaStartFrom",
          "OnThresholdReached",
          "WorkerAction",
          "Effect",
          "LabelAction",
          "BindingTarget",
          "ExpressionEvaluator",
          "CostFunction",
          "FeatureExtractor",
          "Path",
          "QuorumConfig",
          "PlanningConstraints",
          "RecoveryPolicy",
          "AdaptationConfig",
          "EpisodicMemoryConfig",
          "CbrConfig",
          "ReflectionTriggerConfig",
          "PortfolioConfig",
          "MonitoringConfig",
          "MemoryRetrievalConfig",
          "WorkloadConstraint",
          "ContextConstraint",
          "ChannelDeclaration",
          "GoapAction",
          "RecoveryOverride");

  private SchemaPostProcessor() {}

  static void process(ObjectNode schema) {
    renameDef(schema, "ExpressionEvaluator", "ExpressionOrOverride");
    removeUnwantedDefs(schema);
    addMissingDefs(schema);
    extractSpecAsRef(schema);
    cleanSpecProperties(schema);
    expandSpecProperties(schema);
    fixPropertyNames(schema);
    fixRootProperties(schema);
    addLlmProviderConstraints(schema);
    addRequiredAndDefaults(schema);
    fixRemainingStructural(schema);
    addTitlesAndUseConstraints(schema);
    addStringValidation(schema);
    addCodegenDirectives(schema);
    addRootMetadata(schema);
  }

  private static void renameDef(ObjectNode schema, String oldName, String newName) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null || !defs.has(oldName)) {
      return;
    }
    defs.set(newName, defs.get(oldName));
    defs.remove(oldName);
    updateRefs(schema, "#/$defs/" + oldName, "#/$defs/" + newName);
  }

  private static void removeUnwantedDefs(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null) {
      return;
    }
    List<String> toRemove = new ArrayList<>();
    defs.fieldNames()
        .forEachRemaining(
            name -> {
              if (UNWANTED_DEFS.contains(name) || isGenericMapType(name)) {
                toRemove.add(name);
              }
            });
    toRemove.forEach(defs::remove);
  }

  private static boolean isGenericMapType(String name) {
    return name.startsWith("Map(")
        || name.startsWith("SignalType(")
        || name.startsWith("Class(")
        || name.startsWith("ContextBridge(")
        || name.startsWith("CompiledExpression(");
  }

  private static void addMissingDefs(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null) {
      defs = schema.putObject("$defs");
    }
    defs.set("ExpressionOrOverride", buildExpressionOrOverride());
    defs.set("GoalExpression", buildGoalExpression());
    defs.set("CaseDefinitionSpec", buildCaseDefinitionSpec(schema));
    defs.set("HumanTask", buildHumanTask());
    defs.set("SubCase", buildSubCase());
    defs.set("CloudEventTrigger", buildCloudEventTrigger());
    defs.set("ScheduleTrigger", buildScheduleTrigger());
    defs.set("ScopeActivatedTrigger", buildScopeActivatedTrigger());
    defs.set("ContextChangeTrigger", buildContextChangeTrigger());
    defs.set("ExecutionPolicy", buildExecutionPolicy());
    defs.set("RetryPolicy", buildRetryPolicy());
    defs.set("Authorization", buildAuthorization());
    defs.set("Cbr", buildCbr());
    defs.set("Agent", buildAgent());
    defs.set("AgentModel", buildAgentModel());
    buildLlmProviderDefs(defs);
  }

  private static void addRootMetadata(ObjectNode schema) {
    schema.put("$id", "https://casehub.io/schemas/0.1.0/casehub.yaml");
    schema.put("title", "CaseHub");
  }

  private static void extractSpecAsRef(ObjectNode schema) {
    ObjectNode rootProps = (ObjectNode) schema.get("properties");
    if (rootProps == null || !rootProps.has("spec")) {
      return;
    }
    ObjectNode specInline = (ObjectNode) rootProps.get("spec");
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs != null && defs.has("CaseDefinitionSpec")) {
      defs.set("CaseDefinitionSpec", specInline.deepCopy());
    }
    ObjectNode specRef = newObject();
    specRef.put("$ref", "#/$defs/CaseDefinitionSpec");
    rootProps.set("spec", specRef);
  }

  private static final Set<String> SPEC_ONLY_PROPERTIES =
      Set.of(
          "capabilities",
          "workers",
          "bindings",
          "milestones",
          "goals",
          "completion",
          "planningStrategy",
          "decompositionStrategy",
          "maxDecompositionDepth",
          "agentRouting",
          "implementationRouting",
          "humanTaskRouting",
          "candidateMatching",
          "routingSignalWeights",
          "cbr",
          "channels",
          "authorization",
          "reflectionTrigger",
          "monitoringConfig",
          "adaptationConfig",
          "planningConstraints",
          "recoveryPolicy",
          "portfolioConfig",
          "memoryRetrieval",
          "maxAdaptations",
          "goapActions",
          "workerServiceAccountIds",
          "defaultQuorum",
          "humanTaskContextConstraints",
          "humanTaskWorkloadConstraint");

  private static void cleanSpecProperties(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null || !defs.has("CaseDefinitionSpec")) {
      return;
    }
    ObjectNode spec = (ObjectNode) defs.get("CaseDefinitionSpec");
    ObjectNode specProps = (ObjectNode) spec.get("properties");
    if (specProps == null) {
      return;
    }
    List<String> toRemove = new ArrayList<>();
    specProps
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!SPEC_ONLY_PROPERTIES.contains(name)) {
                toRemove.add(name);
              }
            });
    toRemove.forEach(specProps::remove);

    ObjectNode routingWeights = (ObjectNode) specProps.get("routingSignalWeights");
    if (routingWeights != null && routingWeights.has("$ref")) {
      routingWeights.removeAll();
      routingWeights.put("type", "object");
      routingWeights.putObject("additionalProperties").put("type", "number");
    }
    ObjectNode authNode = (ObjectNode) specProps.get("authorization");
    if (authNode != null
        && authNode.has("$ref")
        && authNode.get("$ref").asText().contains("Map(")) {
      authNode.put("$ref", "#/$defs/Authorization");
    }
    ObjectNode cbr = (ObjectNode) specProps.get("cbr");
    if (cbr == null) {
      specProps.putObject("cbr").put("$ref", "#/$defs/Cbr");
    } else if (!cbr.has("$ref")) {
      cbr.removeAll();
      cbr.put("$ref", "#/$defs/Cbr");
    }
    ObjectNode channels = (ObjectNode) specProps.get("channels");
    if (channels != null) {
      ObjectNode items = (ObjectNode) channels.get("items");
      if (items != null && items.has("$ref")) {
        items.removeAll();
        items.put("type", "object");
        items.putArray("required").add("name").add("recordType");
        items.put("unevaluatedProperties", false);
        ObjectNode channelProps = items.putObject("properties");
        addStringProp(channelProps, "name", 1, 0, null);
        addStringProp(channelProps, "recordType", 1, 0, null);
        ObjectNode transport = channelProps.putObject("transport");
        transport.put("type", "string");
        transport.put("default", "in-memory");
        ObjectNode scope = channelProps.putObject("scope");
        scope.put("type", "string");
        scope.putArray("enum").add("COMPOUND").add("CASE");
        scope.put("default", "CASE");
      }
    }
  }

  private static void expandSpecProperties(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null || !defs.has("CaseDefinitionSpec")) {
      return;
    }
    ObjectNode spec = (ObjectNode) defs.get("CaseDefinitionSpec");
    ObjectNode specProps = (ObjectNode) spec.get("properties");
    if (specProps == null) {
      return;
    }

    renameProperty(specProps, "reflectionTrigger", "reflection");
    renameProperty(specProps, "monitoringConfig", "monitoring");
    renameProperty(specProps, "adaptationConfig", "adaptation");

    specProps.set("reflection", buildReflection());
    specProps.set("monitoring", buildMonitoring());
    specProps.set("adaptation", buildAdaptation());
    specProps.set("planningConstraints", buildPlanningConstraints());
    specProps.set("recoveryPolicy", buildRecoveryPolicy());
    specProps.set("portfolioConfig", buildPortfolioConfig());
    specProps.set("memoryRetrieval", buildMemoryRetrieval());
    specProps.set("goapActions", buildGoapActions());
    specProps.set("defaultQuorum", buildDefaultQuorum());
    specProps.set("humanTaskContextConstraints", buildHumanTaskContextConstraints());
    specProps.set("humanTaskWorkloadConstraint", buildHumanTaskWorkloadConstraint());

    ObjectNode ma = specProps.putObject("maxAdaptations");
    ma.put("type", "integer");
    ma.put("minimum", 1);
    ma.put("description", "Maximum adaptation count per compound before Concede. Default: 5.");

    ObjectNode wsai = specProps.putObject("workerServiceAccountIds");
    wsai.put("type", "object");
    wsai.putObject("additionalProperties").put("type", "string");
    wsai.put(
        "description",
        "Map of worker name to service account ID for tenant-specific endpoint resolution.");
  }

  private static void renameProperty(ObjectNode props, String oldName, String newName) {
    if (props.has(oldName) && !props.has(newName)) {
      props.set(newName, props.get(oldName));
      props.remove(oldName);
    }
  }

  private static ObjectNode buildReflection() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Per-case reflection trigger configuration.");
    ObjectNode props = n.putObject("properties");
    props.putObject("enabled").put("type", "boolean");
    ObjectNode threshold = props.putObject("importanceThreshold");
    threshold.put("type", "number");
    threshold.put("minimum", 0);
    threshold.put("maximum", 10);
    ObjectNode maxOutcomes = props.putObject("maxUnreflectedOutcomes");
    maxOutcomes.put("type", "integer");
    maxOutcomes.put("minimum", 1);
    ObjectNode maxMemories = props.putObject("maxSourceMemories");
    maxMemories.put("type", "integer");
    maxMemories.put("minimum", 1);
    ObjectNode weights = props.putObject("importanceWeights");
    weights.put("type", "object");
    weights.putObject("additionalProperties").put("type", "number");
    return n;
  }

  private static ObjectNode buildMonitoring() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Per-case expectation tracking configuration.");
    ObjectNode props = n.putObject("properties");
    ObjectNode enabled = props.putObject("enabled");
    enabled.put("type", "boolean");
    enabled.put("default", true);
    ObjectNode threshold = props.putObject("perCompletionThreshold");
    threshold.put("type", "number");
    threshold.put("minimum", 0);
    threshold.put("maximum", 1);
    threshold.put("default", 0.5);
    ObjectNode windowSize = props.putObject("windowSize");
    windowSize.put("type", "integer");
    windowSize.put("minimum", 1);
    windowSize.put("default", 5);
    return n;
  }

  private static ObjectNode buildAdaptation() {
    ObjectNode n = newObject();
    n.put(
        "description", "Per-case plan adaptation configuration. String preset or explicit object.");
    ArrayNode oneOf = n.putArray("oneOf");
    ObjectNode stringVariant = oneOf.addObject();
    stringVariant.put("type", "string");
    stringVariant.putArray("enum").add("adaptive").add("conservative").add("off").add("progress");
    ObjectNode objVariant = oneOf.addObject();
    objVariant.put("type", "object");
    objVariant.put("unevaluatedProperties", false);
    ObjectNode objProps = objVariant.putObject("properties");
    objProps.putObject("trigger").put("type", "string");
    objProps.putObject("optimization").put("type", "string");
    objProps.putObject("revision").put("type", "string");
    ObjectNode objThreshold = objProps.putObject("threshold");
    objThreshold.put("type", "number");
    objThreshold.put("minimum", 0);
    objThreshold.put("maximum", 1);
    objProps.putObject("metaReasoner").put("type", "string");
    objProps.putObject("repair").put("type", "string");
    ObjectNode contThreshold = objProps.putObject("contingencyThreshold");
    contThreshold.put("type", "number");
    contThreshold.put("minimum", 0);
    contThreshold.put("maximum", 1);
    return n;
  }

  private static ObjectNode buildPlanningConstraints() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Per-case resource constraints for decomposition and pattern execution.");
    ObjectNode props = n.putObject("properties");
    ObjectNode timeBudget = props.putObject("timeBudget");
    timeBudget.put("type", "string");
    timeBudget.put("description", "ISO-8601 Duration (e.g. PT30M).");
    ObjectNode resourceLimit = props.putObject("resourceLimit");
    resourceLimit.put("type", "integer");
    resourceLimit.put("minimum", 1);
    ObjectNode weights = props.putObject("weights");
    weights.put("type", "object");
    weights.putObject("additionalProperties").put("type", "number");
    ObjectNode costBudgets = props.putObject("costBudgets");
    costBudgets.put("type", "object");
    costBudgets.putObject("additionalProperties").put("type", "integer");
    return n;
  }

  private static ObjectNode buildRecoveryPolicy() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Per-case multi-level recovery configuration.");
    ObjectNode props = n.putObject("properties");
    ObjectNode maxRetries = props.putObject("maxRetries");
    maxRetries.put("type", "integer");
    maxRetries.put("minimum", 0);
    maxRetries.put("default", 3);
    ObjectNode maxReroute = props.putObject("maxRerouteAttempts");
    maxReroute.put("type", "integer");
    maxReroute.put("minimum", 0);
    maxReroute.put("default", 3);
    props.putObject("classifierId").put("type", "string");
    props.putObject("revisionStrategyId").put("type", "string");
    props.putObject("replanStrategyId").put("type", "string");
    ObjectNode enabled = props.putObject("enabled");
    enabled.put("type", "boolean");
    enabled.put("default", true);
    return n;
  }

  private static ObjectNode buildPortfolioConfig() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Cascading decomposition strategy configuration.");
    ObjectNode props = n.putObject("properties");
    ObjectNode delegates = props.putObject("delegates");
    delegates.put("type", "array");
    delegates.putObject("items").put("type", "string");
    ObjectNode timeouts = props.putObject("timeouts");
    timeouts.put("type", "object");
    timeouts.putObject("additionalProperties").put("type", "integer");
    return n;
  }

  private static ObjectNode buildMemoryRetrieval() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Per-case memory retrieval configuration.");
    ObjectNode props = n.putObject("properties");
    ObjectNode enabled = props.putObject("enabled");
    enabled.put("type", "boolean");
    enabled.put("default", true);
    ObjectNode maxMemories = props.putObject("maxMemories");
    maxMemories.put("type", "integer");
    maxMemories.put("minimum", 1);
    ObjectNode domains = props.putObject("domains");
    domains.put("type", "array");
    domains.putObject("items").put("type", "string");
    return n;
  }

  private static ObjectNode buildGoapActions() {
    ObjectNode n = newObject();
    n.put("type", "array");
    n.put("description", "GOAP action declarations for planning.");
    ObjectNode items = n.putObject("items");
    items.put("type", "object");
    items.put("unevaluatedProperties", false);
    items.putArray("required").add("name").add("effects");
    ObjectNode itemProps = items.putObject("properties");
    addStringProp(itemProps, "name", 1, 0, null);
    ObjectNode preconditions = itemProps.putObject("preconditions");
    preconditions.put("type", "object");
    preconditions.putObject("additionalProperties").put("type", "boolean");
    ObjectNode effects = itemProps.putObject("effects");
    effects.put("type", "object");
    effects.putObject("additionalProperties").put("type", "boolean");
    ObjectNode cost = itemProps.putObject("cost");
    cost.put("type", "number");
    cost.put("minimum", 0);
    cost.put("default", 1.0);
    ObjectNode benefit = itemProps.putObject("benefit");
    benefit.put("type", "number");
    benefit.put("minimum", 0);
    benefit.put("default", 0);
    ObjectNode softPreconditions = itemProps.putObject("softPreconditions");
    softPreconditions.put("type", "object");
    softPreconditions.putObject("additionalProperties").put("type", "boolean");
    return n;
  }

  private static ObjectNode buildDefaultQuorum() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Default M-of-N multi-party approval configuration for action gates.");
    n.putArray("required").add("instances").add("required");
    ObjectNode props = n.putObject("properties");
    ObjectNode instances = props.putObject("instances");
    instances.put("type", "integer");
    instances.put("minimum", 2);
    ObjectNode required = props.putObject("required");
    required.put("type", "integer");
    required.put("minimum", 1);
    ObjectNode otr = props.putObject("onThresholdReached");
    otr.put("type", "string");
    otr.putArray("enum").add("KEEP").add("CANCEL");
    otr.put("default", "KEEP");
    ObjectNode allowSame = props.putObject("allowSameAssignee");
    allowSame.put("type", "boolean");
    allowSame.put("default", false);
    return n;
  }

  private static ObjectNode buildHumanTaskContextConstraints() {
    ObjectNode n = newObject();
    n.put("type", "array");
    n.put("description", "Declarative rules for humanTask candidate filtering and scoring.");
    ObjectNode items = n.putObject("items");
    items.put("type", "object");
    items.put("unevaluatedProperties", false);
    items.putArray("required").add("when").add("effect");
    ObjectNode itemProps = items.putObject("properties");
    itemProps.putObject("when").put("$ref", "#/$defs/ExpressionOrOverride");
    ObjectNode effect = itemProps.putObject("effect");
    effect.put("type", "object");
    effect.put("unevaluatedProperties", false);
    ObjectNode effectProps = effect.putObject("properties");
    ObjectNode preferGroups = effectProps.putObject("preferGroups");
    preferGroups.put("type", "array");
    preferGroups.putObject("items").put("type", "string");
    ObjectNode preferUsers = effectProps.putObject("preferUsers");
    preferUsers.put("type", "array");
    preferUsers.putObject("items").put("type", "string");
    ObjectNode excludeGroups = effectProps.putObject("excludeGroups");
    excludeGroups.put("type", "array");
    excludeGroups.putObject("items").put("type", "string");
    ObjectNode excludeUsers = effectProps.putObject("excludeUsers");
    excludeUsers.put("type", "array");
    excludeUsers.putObject("items").put("type", "string");
    ObjectNode weight = itemProps.putObject("weight");
    weight.put("type", "number");
    weight.put("minimum", 0);
    weight.put("maximum", 1);
    weight.put("default", 1.0);
    return n;
  }

  private static ObjectNode buildHumanTaskWorkloadConstraint() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("description", "Workload-based constraints for humanTask candidate filtering.");
    ObjectNode props = n.putObject("properties");
    ObjectNode maxActive = props.putObject("maxActiveTaskCount");
    maxActive.put("type", "integer");
    maxActive.put("minimum", 1);
    ObjectNode lbWeight = props.putObject("loadBalanceWeight");
    lbWeight.put("type", "number");
    lbWeight.put("minimum", 0);
    lbWeight.put("maximum", 1);
    return n;
  }

  private static void fixPropertyNames(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null) {
      return;
    }
    renameAndExcludeProperties(
        defs,
        "Binding",
        Map.of("replanHint", "replanAfter"),
        Set.of(
            "sideEffectClassification",
            "recoveryOverride",
            "exchangeProjectionStrategy",
            "exchangeProjectionExpression"));
    ObjectNode binding = (ObjectNode) defs.path("Binding").path("properties");
    if (!binding.isMissingNode()) {
      if (!binding.has("exchangeProjection")) {
        ObjectNode ep = binding.putObject("exchangeProjection");
        ep.put("type", "string");
        ep.put("description", "Exchange projection strategy ID or JQ expression.");
      }
      ObjectNode contextWrite = (ObjectNode) binding.get("contextWrite");
      if (contextWrite != null && contextWrite.has("$ref")) {
        contextWrite.removeAll();
        contextWrite.put("type", "object");
        contextWrite.put("additionalProperties", true);
      }
      ObjectNode inputProjectionOverride = (ObjectNode) binding.get("inputProjectionOverride");
      if (inputProjectionOverride != null && inputProjectionOverride.has("$ref")) {
        inputProjectionOverride.removeAll();
        inputProjectionOverride.put("type", "string");
      }
      ObjectNode permissionIntent = (ObjectNode) binding.get("permissionIntent");
      if (permissionIntent != null) {
        ObjectNode items = (ObjectNode) permissionIntent.get("items");
        if (items != null && items.has("$ref")) {
          items.removeAll();
          items.put("type", "string");
        }
      }
    }
    renameAndExcludeProperties(
        defs, "InboundSignalMapping", Map.of("signalName", "signal"), Set.of());
    ObjectNode inboundProps = (ObjectNode) defs.path("InboundSignalMapping").path("properties");
    if (!inboundProps.isMissingNode()) {
      ObjectNode signal = (ObjectNode) inboundProps.get("signal");
      if (signal == null) {
        inboundProps.putObject("signal").put("type", "string");
      }
      for (String field : List.of("correlation", "payload")) {
        ObjectNode f = (ObjectNode) inboundProps.get(field);
        if (f != null && f.has("$ref")) {
          f.removeAll();
          f.put("type", "string");
        }
      }
    }
    renameAndExcludeProperties(
        defs, "LabelRule", Map.of("condition", "when"), Set.of("triggerEvents"));
    renameAndExcludeProperties(
        defs, "Milestone", Map.of("completionCriteria", "condition"), Set.of());
    ObjectNode milestoneProps = (ObjectNode) defs.path("Milestone").path("properties");
    if (!milestoneProps.isMissingNode()) {
      ObjectNode slaDuration = (ObjectNode) milestoneProps.get("slaDuration");
      if (slaDuration != null) {
        slaDuration.remove("format");
      }
    }
    // Capability field names aligned with worker-api — no rename needed
  }

  private static void renameAndExcludeProperties(
      ObjectNode defs, String typeName, Map<String, String> renames, Set<String> excludes) {
    JsonNode typeNode = defs.get(typeName);
    if (typeNode == null || !typeNode.has("properties")) {
      return;
    }
    ObjectNode props = (ObjectNode) typeNode.get("properties");
    for (var entry : renames.entrySet()) {
      if (props.has(entry.getKey()) && !props.has(entry.getValue())) {
        props.set(entry.getValue(), props.get(entry.getKey()));
        props.remove(entry.getKey());
      }
    }
    excludes.forEach(props::remove);
  }

  private static void fixRootProperties(ObjectNode schema) {
    ObjectNode rootProps = (ObjectNode) schema.get("properties");
    if (rootProps == null) {
      return;
    }
    rootProps.remove("episodicMemoryConfig");
    rootProps.remove("layerNames");
    rootProps.remove("contextStoreFactory");
    if (!rootProps.has("episodic")) {
      ObjectNode episodic = rootProps.putObject("episodic");
      episodic.put("type", "object");
      episodic.put("unevaluatedProperties", false);
      ObjectNode memProps = episodic.putObject("properties").putObject("memory");
      memProps.put("type", "object");
      memProps.putArray("required").add("domain").add("entityId");
      memProps.put("unevaluatedProperties", false);
      ObjectNode memInnerProps = memProps.putObject("properties");
      memInnerProps.putObject("domain").put("type", "string");
      memInnerProps.putObject("entityId").put("type", "string");
      ObjectNode recent = memInnerProps.putObject("recent");
      recent.put("type", "integer");
      recent.put("default", 10);
      recent.put("minimum", 1);
    }
    if (!rootProps.has("expressionLang")) {
      ObjectNode el = rootProps.putObject("expressionLang");
      el.put("type", "string");
      el.put("title", "ExpressionLang");
      el.put("minLength", 1);
      el.put("maxLength", 64);
      el.put("default", "jq");
    }
    if (!rootProps.has("contextType")) {
      ObjectNode ct = rootProps.putObject("contextType");
      ct.put("type", "string");
      ct.put("title", "ContextType");
      ct.put("minLength", 1);
      ct.put("maxLength", 512);
    }
    if (!rootProps.has("layers")) {
      ObjectNode layers = rootProps.putObject("layers");
      layers.put("type", "array");
      ObjectNode layerItem = layers.putObject("items");
      layerItem.put("type", "object");
      layerItem.putArray("required").add("name");
      layerItem.put("unevaluatedProperties", false);
      layerItem.putObject("properties").putObject("name").put("type", "string");
    }
    if (!rootProps.has("context")) {
      ObjectNode context = rootProps.putObject("context");
      context.put("type", "object");
      context.put("unevaluatedProperties", false);
      context.putObject("properties").putObject("storeFactory").put("type", "string");
    }
    ObjectNode semanticData = (ObjectNode) rootProps.get("semanticData");
    if (semanticData != null && semanticData.has("$ref")) {
      semanticData.removeAll();
      semanticData.put("type", "object");
      semanticData.put("additionalProperties", true);
    }
    ObjectNode types = (ObjectNode) rootProps.get("types");
    if (types != null) {
      ObjectNode items = (ObjectNode) types.get("items");
      if (items != null && items.has("$ref")) {
        items.removeAll();
        items.put("type", "string");
        items.put("minLength", 1);
      }
    }
    ObjectNode labels = (ObjectNode) rootProps.get("labels");
    if (labels != null) {
      ObjectNode items = (ObjectNode) labels.get("items");
      if (items != null && items.has("$ref")) {
        items.removeAll();
        items.put("type", "string");
        items.put("minLength", 1);
      }
    }
    ObjectNode signals = (ObjectNode) rootProps.get("signals");
    if (signals != null) {
      ObjectNode items = (ObjectNode) signals.get("items");
      if (items != null && items.has("$ref")) {
        items.removeAll();
        items.put("type", "object");
        items.putArray("required").add("name").add("payloadType");
        items.put("unevaluatedProperties", false);
        ObjectNode sigProps = items.putObject("properties");
        sigProps.putObject("name").put("type", "string");
        sigProps.putObject("payloadType").put("type", "string");
      }
    }
  }

  private static void addLlmProviderConstraints(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null) {
      return;
    }
    Map<String, Map<String, double[]>> constraints =
        Map.of(
            "OpenAiModel",
            Map.of(
                "temperature", new double[] {0.0, 2.0},
                "topP", new double[] {0.0, 1.0},
                "frequencyPenalty", new double[] {-2.0, 2.0},
                "presencePenalty", new double[] {-2.0, 2.0}),
            "AnthropicModel",
            Map.of(
                "temperature", new double[] {0.0, 1.0},
                "topP", new double[] {0.0, 1.0}),
            "MistralAiModel",
            Map.of(
                "temperature", new double[] {0.0, 1.0},
                "topP", new double[] {0.0, 1.0}),
            "GoogleAiGeminiModel",
            Map.of(
                "temperature", new double[] {0.0, 1.0},
                "topP", new double[] {0.0, 1.0}),
            "OllamaModel",
            Map.of(
                "temperature", new double[] {0.0, 2.0},
                "topP", new double[] {0.0, 1.0}));
    for (var modelEntry : constraints.entrySet()) {
      ObjectNode modelDef = (ObjectNode) defs.get(modelEntry.getKey());
      if (modelDef == null) {
        continue;
      }
      ObjectNode props = (ObjectNode) modelDef.get("properties");
      if (props == null) {
        continue;
      }
      for (var fieldEntry : modelEntry.getValue().entrySet()) {
        ObjectNode field = (ObjectNode) props.get(fieldEntry.getKey());
        if (field != null) {
          field.put("minimum", fieldEntry.getValue()[0]);
          field.put("maximum", fieldEntry.getValue()[1]);
        }
      }
    }
  }

  private static void addRequiredAndDefaults(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null) {
      return;
    }
    addRequired(
        defs, "InboundSignalMapping", List.of("signal", "connectorType", "correlation", "payload"));
    addRequired(defs, "LabelRule", List.of("name", "when", "actions"));
    addRequired(defs, "Goal", List.of("name", "condition"));
    addRequired(defs, "Binding", List.of("on"));
    addRequired(defs, "Capability", List.of("name"));
    addRequired(defs, "Milestone", List.of("name", "condition"));

    schema.putArray("required").add("dsl").add("namespace").add("name").add("version").add("spec");

    addDefault(defs, "OutcomePolicy", "onDecline", "REROUTE");
    addDefault(defs, "OutcomePolicy", "onFailure", "REROUTE");
    addDefault(defs, "OutcomePolicy", "onExpired", "REROUTE");
    addDefaultInt(defs, "OutcomePolicy", "maxRerouteAttempts", 3);
    addDefault(defs, "Binding", "participation", "PARTICIPANT");
    addDefault(defs, "Binding", "executionMode", "TRANSIENT");
    addDefault(defs, "Binding", "lifecycleScope", "BINDING");
    addDefault(defs, "Binding", "conflictResolverStrategy", "LAST_WRITER_WINS");
    addDefault(defs, "Binding", "replanAfter", "conditional");
    addDefault(defs, "Goal", "kind", "success");
    addDefault(defs, "Milestone", "slaStartFrom", "MILESTONE_ACTIVATED");

    addMinimum(defs, "OutcomePolicy", "maxRerouteAttempts", 1);
    addMinMax(defs, "CaseDefinitionSpec", "maxDecompositionDepth", 1, 10);
  }

  private static void addRequired(ObjectNode defs, String typeName, List<String> fields) {
    ObjectNode type = (ObjectNode) defs.get(typeName);
    if (type == null || type.has("required")) {
      return;
    }
    ArrayNode req = type.putArray("required");
    fields.forEach(req::add);
  }

  private static void addDefault(ObjectNode defs, String typeName, String field, String value) {
    ObjectNode type = (ObjectNode) defs.get(typeName);
    if (type == null) {
      return;
    }
    ObjectNode prop = (ObjectNode) type.path("properties").path(field);
    if (!prop.isMissingNode() && !prop.has("default")) {
      prop.put("default", value);
    }
  }

  private static void addDefaultInt(ObjectNode defs, String typeName, String field, int value) {
    ObjectNode type = (ObjectNode) defs.get(typeName);
    if (type == null) {
      return;
    }
    ObjectNode prop = (ObjectNode) type.path("properties").path(field);
    if (!prop.isMissingNode() && !prop.has("default")) {
      prop.put("default", value);
    }
  }

  private static void addMinimum(ObjectNode defs, String typeName, String field, int min) {
    ObjectNode type = (ObjectNode) defs.get(typeName);
    if (type == null) {
      return;
    }
    ObjectNode prop = (ObjectNode) type.path("properties").path(field);
    if (!prop.isMissingNode() && !prop.has("minimum")) {
      prop.put("minimum", min);
    }
  }

  private static void addMinMax(ObjectNode defs, String typeName, String field, int min, int max) {
    ObjectNode type = (ObjectNode) defs.get(typeName);
    if (type == null) {
      return;
    }
    ObjectNode prop = (ObjectNode) type.path("properties").path(field);
    if (!prop.isMissingNode()) {
      if (!prop.has("minimum")) {
        prop.put("minimum", min);
      }
      if (!prop.has("maximum")) {
        prop.put("maximum", max);
      }
    }
  }

  private static void fixRemainingStructural(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null) {
      return;
    }
    ObjectNode labelRuleProps = (ObjectNode) defs.path("LabelRule").path("properties");
    if (!labelRuleProps.isMissingNode()) {
      ObjectNode when = (ObjectNode) labelRuleProps.get("when");
      if (when != null && when.has("$ref")) {
        when.removeAll();
        when.put("type", "string");
      }
      ObjectNode actions = (ObjectNode) labelRuleProps.get("actions");
      if (actions != null) {
        if (!actions.has("minItems")) {
          actions.put("minItems", 1);
        }
        ObjectNode items = (ObjectNode) actions.get("items");
        if (items != null && items.has("$ref")) {
          items.removeAll();
          items.put("type", "object");
          items.put("unevaluatedProperties", false);
          ArrayNode oneOf = items.putArray("oneOf");
          oneOf.addObject().putArray("required").add("add");
          oneOf.addObject().putArray("required").add("remove");
          ObjectNode actionProps = items.putObject("properties");
          actionProps.putObject("add").put("type", "string");
          actionProps.putObject("remove").put("type", "string");
        }
      }
    }
    ObjectNode bindingProps = (ObjectNode) defs.path("Binding").path("properties");
    if (!bindingProps.isMissingNode()) {
      ObjectNode replanAfter = (ObjectNode) bindingProps.get("replanAfter");
      if (replanAfter != null && replanAfter.has("enum")) {
        ArrayNode enumValues = (ArrayNode) replanAfter.get("enum");
        ArrayNode newEnum = replanAfter.putArray("enum");
        for (JsonNode v : enumValues) {
          newEnum.add(v.asText().toLowerCase());
        }
      }
      ObjectNode conflictResolver = (ObjectNode) bindingProps.get("conflictResolverStrategy");
      if (conflictResolver != null && !conflictResolver.has("enum")) {
        conflictResolver
            .putArray("enum")
            .add("LAST_WRITER_WINS")
            .add("FIRST_WRITER_WINS")
            .add("FAIL")
            .add("DEEP_MERGE");
      }
      ObjectNode ep = (ObjectNode) bindingProps.get("exchangeProjection");
      if (ep != null && !ep.has("oneOf")) {
        ep.removeAll();
        ArrayNode epOneOf = ep.putArray("oneOf");
        epOneOf.addObject().put("type", "string");
        ObjectNode epObj = epOneOf.addObject();
        epObj.put("type", "object");
        epObj.put("unevaluatedProperties", false);
        epObj.putArray("required").add("strategy");
        ObjectNode epProps = epObj.putObject("properties");
        epProps.putObject("strategy").put("type", "string");
        epProps.putObject("expression").put("type", "string");
      }
    }
    ObjectNode milestoneProps = (ObjectNode) defs.path("Milestone").path("properties");
    if (!milestoneProps.isMissingNode()) {
      ObjectNode slaStartFrom = (ObjectNode) milestoneProps.get("slaStartFrom");
      if (slaStartFrom != null) {
        slaStartFrom
            .putArray("enum")
            .add("CASE_CREATED")
            .add("MILESTONE_ACTIVATED")
            .add("PREVIOUS_MILESTONE_COMPLETED")
            .add("EVENT_OCCURRED");
        if (!slaStartFrom.has("default")) {
          slaStartFrom.put("default", "MILESTONE_ACTIVATED");
        }
      }
    }
    ObjectNode subCaseProps = (ObjectNode) defs.path("SubCase").path("properties");
    if (!subCaseProps.isMissingNode()) {
      if (!subCaseProps.has("onThresholdReached")) {
        ObjectNode otr = subCaseProps.putObject("onThresholdReached");
        otr.put("type", "string");
        otr.putArray("enum").add("KEEP").add("CANCEL");
        otr.put("default", "KEEP");
      }
      ObjectNode version = (ObjectNode) subCaseProps.get("version");
      if (version != null && !version.has("pattern")) {
        version.put(
            "pattern",
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
                + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");
      }
    }
    ObjectNode capabilityProps = (ObjectNode) defs.path("Capability").path("properties");
    if (!capabilityProps.isMissingNode() && !capabilityProps.has("cognitiveDemand")) {
      ObjectNode cd = capabilityProps.putObject("cognitiveDemand");
      cd.put("type", "object");
      ObjectNode adp = cd.putObject("additionalProperties");
      adp.put("type", "number");
      adp.put("minimum", 0);
      adp.put("maximum", 1);
    }
    ObjectNode rootProps = (ObjectNode) schema.get("properties");
    if (rootProps != null) {
      ObjectNode signalItems =
          (ObjectNode) rootProps.path("signals").path("items").path("properties");
      if (!signalItems.isMissingNode()) {
        if (signalItems.has("payloadType") && !signalItems.has("contextType")) {
          signalItems.set("contextType", signalItems.get("payloadType"));
          signalItems.remove("payloadType");
        }
        ObjectNode signalReq = (ObjectNode) rootProps.path("signals").path("items");
        if (signalReq.has("required")) {
          signalReq.putArray("required").add("name").add("contextType");
        }
      }
    }
  }

  private static void addTitlesAndUseConstraints(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    ObjectNode rootProps = (ObjectNode) schema.get("properties");
    if (rootProps != null) {
      setTitle(rootProps, "namespace", "CaseHubNamespace");
      setTitle(rootProps, "name", "CaseHubName");
      setTitle(rootProps, "version", "CaseHubVersion");
      setTitle(rootProps, "dsl", "CaseHubDSL");
      setTitle(rootProps, "title", "CaseHubTitle");
      setTitle(rootProps, "summary", "CaseHubSummary");
      setTitle(rootProps, "types", "CaseHubTypes");
      setTitle(rootProps, "labels", "CaseHubLabels");
      setTitle(rootProps, "signals", "CaseSignals");
      setTitle(rootProps, "inboundMappings", "CaseInboundMappings");
      setTitle(rootProps, "labelRules", "CaseLabelRules");
    }
    if (defs != null) {
      setTitle(defs, "Worker", "properties", "name", "WorkerName");
      setTitle(defs, "Worker", "properties", "capabilities", "WorkerCapabilities");
      setTitle(defs, "Capability", "properties", "name", "CapabilityName");
      setTitle(defs, "Milestone", "properties", "name", "MilestoneName");
      setTitle(defs, "Milestone", "properties", "condition", "MilestoneCondition");

      ObjectNode use = (ObjectNode) defs.get("Use");
      if (use != null) {
        ObjectNode useProps = (ObjectNode) use.get("properties");
        if (useProps != null) {
          addArrayItemConstraints(
              useProps,
              "configMaps",
              true,
              1,
              63,
              "^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
          addArrayItemConstraints(
              useProps, "secrets", true, 1, 63, "^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
        }
      }
    }
  }

  private static void addStringValidation(ObjectNode schema) {
    ObjectNode rootProps = (ObjectNode) schema.get("properties");
    if (rootProps != null) {
      String identifierPattern = "^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$";
      String semverPattern =
          "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
              + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
              + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
              + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$";
      addStringConstraints(rootProps, "namespace", 1, 255, identifierPattern);
      addStringConstraints(rootProps, "name", 1, 255, identifierPattern);
      addStringConstraints(rootProps, "version", 1, 50, semverPattern);
      addStringConstraints(rootProps, "dsl", 1, 50, semverPattern);
      addStringConstraints(rootProps, "title", 0, 500, null);
      ObjectNode titleProp = (ObjectNode) rootProps.get("title");
      if (titleProp != null && !titleProp.has("minLength")) {
        titleProp.put("minLength", 0);
      }
      ObjectNode signalItems =
          (ObjectNode) rootProps.path("signals").path("items").path("properties");
      if (!signalItems.isMissingNode()) {
        addMinLength(signalItems, "name", 1);
        addMinLength(signalItems, "contextType", 1);
      }
    }
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs != null) {
      addMinLength(defProps(defs, "InboundSignalMapping"), "connectorType", 1);
      addMinLength(defProps(defs, "InboundSignalMapping"), "signal", 1);
      addMinLength(defProps(defs, "LabelRule"), "name", 1);
      addMaxLength(defProps(defs, "Worker"), "name", 255);
    }
  }

  private static void addCodegenDirectives(ObjectNode schema) {
    ObjectNode defs = (ObjectNode) schema.get("$defs");
    if (defs == null) {
      return;
    }
    ObjectNode spec = (ObjectNode) defs.get("CaseDefinitionSpec");
    if (spec == null) {
      return;
    }
    ObjectNode specProps = (ObjectNode) spec.get("properties");
    if (specProps == null) {
      return;
    }

    Map<String, String> directives =
        Map.of(
            "_codegenAgent", "Agent",
            "_codegenAgentModel", "AgentModel",
            "_codegenOpenAi", "OpenAiModel",
            "_codegenOllama", "OllamaModel",
            "_codegenAnthropic", "AnthropicModel",
            "_codegenMistral", "MistralAiModel",
            "_codegenGoogleAi", "GoogleAiGeminiModel");
    for (var entry : directives.entrySet()) {
      ObjectNode prop = specProps.putObject(entry.getKey());
      prop.put(
          "description",
          "Code generation directive — ensures "
              + entry.getValue()
              + " type is generated by jsonschema2pojo. Not a user-facing property.");
      prop.put("$ref", "#/$defs/" + entry.getValue());
    }
  }

  private static ObjectNode defProps(ObjectNode defs, String typeName) {
    JsonNode type = defs.get(typeName);
    if (type == null) {
      return newObject();
    }
    JsonNode props = type.get("properties");
    return props instanceof ObjectNode ? (ObjectNode) props : newObject();
  }

  private static void addStringConstraints(
      ObjectNode props, String field, int minLength, int maxLength, String pattern) {
    ObjectNode prop = (ObjectNode) props.get(field);
    if (prop == null) {
      return;
    }
    if (minLength > 0 && !prop.has("minLength")) {
      prop.put("minLength", minLength);
    }
    if (maxLength > 0 && !prop.has("maxLength")) {
      prop.put("maxLength", maxLength);
    }
    if (pattern != null && !prop.has("pattern")) {
      prop.put("pattern", pattern);
    }
  }

  private static void addMinLength(ObjectNode props, String field, int minLength) {
    ObjectNode prop = (ObjectNode) props.get(field);
    if (prop != null && !prop.has("minLength")) {
      prop.put("minLength", minLength);
    }
  }

  private static void addMaxLength(ObjectNode props, String field, int maxLength) {
    ObjectNode prop = (ObjectNode) props.get(field);
    if (prop != null && !prop.has("maxLength")) {
      prop.put("maxLength", maxLength);
    }
  }

  private static void setTitle(ObjectNode props, String field, String title) {
    ObjectNode prop = (ObjectNode) props.get(field);
    if (prop != null && !prop.has("title")) {
      prop.put("title", title);
    }
  }

  private static void setTitle(
      ObjectNode defs, String type, String propsKey, String field, String title) {
    ObjectNode typeNode = (ObjectNode) defs.get(type);
    if (typeNode == null) {
      return;
    }
    ObjectNode props = (ObjectNode) typeNode.get(propsKey);
    if (props != null) {
      setTitle(props, field, title);
    }
  }

  private static void addArrayItemConstraints(
      ObjectNode props,
      String field,
      boolean uniqueItems,
      int minLength,
      int maxLength,
      String pattern) {
    ObjectNode arr = (ObjectNode) props.get(field);
    if (arr == null) {
      return;
    }
    if (uniqueItems && !arr.has("uniqueItems")) {
      arr.put("uniqueItems", true);
    }
    ObjectNode items = (ObjectNode) arr.get("items");
    if (items != null) {
      if (minLength > 0 && !items.has("minLength")) {
        items.put("minLength", minLength);
      }
      if (maxLength > 0 && !items.has("maxLength")) {
        items.put("maxLength", maxLength);
      }
      if (pattern != null && !items.has("pattern")) {
        items.put("pattern", pattern);
      }
    }
  }

  // --- $ref tree walker ---

  private static void updateRefs(JsonNode node, String oldRef, String newRef) {
    if (node.isObject()) {
      ObjectNode obj = (ObjectNode) node;
      if (obj.has("$ref") && oldRef.equals(obj.get("$ref").asText())) {
        obj.put("$ref", newRef);
      }
      obj.fields().forEachRemaining(e -> updateRefs(e.getValue(), oldRef, newRef));
    } else if (node.isArray()) {
      node.forEach(child -> updateRefs(child, oldRef, newRef));
    }
  }

  // --- Missing $def builders ---

  private static ObjectNode newObject() {
    return new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
  }

  private static ObjectNode buildExpressionOrOverride() {
    ObjectNode n = newObject();
    n.put(
        "description",
        "Expression string or per-expression language override map."
            + " Plain string uses the definition-level expressionLang."
            + " Map syntax overrides: { jq: \".expr\" } or { mvel: \"expr\" }.");
    ArrayNode oneOf = n.putArray("oneOf");
    oneOf.addObject().put("type", "string");
    ObjectNode mapVariant = oneOf.addObject();
    mapVariant.put("type", "object");
    mapVariant.put("minProperties", 1);
    mapVariant.put("maxProperties", 1);
    mapVariant.putObject("additionalProperties").put("type", "string");
    return n;
  }

  private static ObjectNode buildGoalExpression() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    ArrayNode oneOf = n.putArray("oneOf");
    oneOf.addObject().putArray("required").add("allOf");
    oneOf.addObject().putArray("required").add("anyOf");
    ObjectNode props = n.putObject("properties");
    for (String kind : List.of("allOf", "anyOf")) {
      ObjectNode arr = props.putObject(kind);
      arr.put("type", "array");
      arr.put("minItems", 1);
      ArrayNode itemsOneOf = arr.putObject("items").putArray("oneOf");
      itemsOneOf.addObject().put("type", "string");
      itemsOneOf.addObject().put("$ref", "#/$defs/GoalExpression");
    }
    return n;
  }

  private static ObjectNode buildCaseDefinitionSpec(ObjectNode rootSchema) {
    JsonNode inlineSpec = rootSchema.path("properties").path("spec");
    if (!inlineSpec.isMissingNode() && inlineSpec.isObject()) {
      return inlineSpec.deepCopy();
    }
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", true);
    return n;
  }

  private static ObjectNode buildHumanTask() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put(
        "description",
        "A binding target that creates a WorkItem in casehub-work"
            + " and resumes the case when the WorkItem reaches a terminal state.");
    ArrayNode oneOf = n.putArray("oneOf");
    oneOf.addObject().putArray("required").add("title");
    oneOf.addObject().putArray("required").add("titleExpression");
    oneOf.addObject().putArray("required").add("templateRef");
    ObjectNode props = n.putObject("properties");
    props.putObject("title").put("type", "string");
    props.putObject("titleExpression").put("type", "string");
    props.putObject("templateRef").put("type", "string");
    props.putObject("inputMapping").put("type", "string");
    props.putObject("outputMapping").put("type", "string");
    props.putObject("scope").put("type", "string");
    props.putObject("scopeExpression").put("type", "string");
    ObjectNode candidateGroups = props.putObject("candidateGroups");
    ArrayNode cgOneOf = candidateGroups.putArray("oneOf");
    ObjectNode cgArray = cgOneOf.addObject();
    cgArray.put("type", "array");
    cgArray.putObject("items").put("type", "string");
    cgOneOf.addObject().put("type", "string");
    ObjectNode candidateUsers = props.putObject("candidateUsers");
    ArrayNode cuOneOf = candidateUsers.putArray("oneOf");
    ObjectNode cuArray = cuOneOf.addObject();
    cuArray.put("type", "array");
    cuArray.putObject("items").put("type", "string");
    cuOneOf.addObject().put("type", "string");
    props.putObject("expiresIn").put("type", "string");
    props.putObject("expiresInExpression").put("type", "string");
    ObjectNode claimDeadline = props.putObject("claimDeadlineHours");
    claimDeadline.put("type", "integer");
    claimDeadline.put("minimum", 1);
    props.putObject("expiresAtExpression").put("type", "string");
    ObjectNode outcomes = props.putObject("outcomes");
    outcomes.put("type", "array");
    outcomes.putObject("items").put("type", "string");
    props.putObject("payloadType").put("type", "string");
    props.putObject("resolutionType").put("type", "string");
    return n;
  }

  private static ObjectNode buildSubCase() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.putArray("required").add("namespace").add("name").add("version");
    ObjectNode props = n.putObject("properties");
    addStringProp(props, "namespace", 1, 255, "^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
    addStringProp(props, "name", 1, 255, "^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
    addStringProp(props, "version", 1, 50, null);
    ObjectNode completionStrategy = props.putObject("completionStrategy");
    completionStrategy.put("type", "string");
    completionStrategy.putArray("enum").add("DEFAULT").add("CUSTOM");
    completionStrategy.put("default", "DEFAULT");
    ObjectNode waitForCompletion = props.putObject("waitForCompletion");
    waitForCompletion.put("type", "boolean");
    waitForCompletion.put("default", true);
    ObjectNode inputMapping = props.putObject("inputMapping");
    inputMapping.put("type", "string");
    inputMapping.put("default", ".");
    props.putObject("outputMapping").put("type", "string");
    ObjectNode maxRecursion = props.putObject("maxRecursionDepth");
    maxRecursion.put("type", "integer");
    maxRecursion.put("minimum", 0);
    maxRecursion.put("maximum", 20);
    maxRecursion.put("default", 0);
    addStringProp(props, "groupId", 1, 255, null);
    ObjectNode totalInGroup = props.putObject("totalInGroup");
    totalInGroup.put("type", "integer");
    totalInGroup.put("minimum", 1);
    ObjectNode requiredCount = props.putObject("requiredCount");
    requiredCount.put("type", "integer");
    requiredCount.put("minimum", 1);
    return n;
  }

  private static ObjectNode buildCloudEventTrigger() {
    ObjectNode n = newObject();
    n.put("description", "Fires on matching CloudEvents.");
    ArrayNode oneOf = n.putArray("oneOf");
    oneOf.addObject().put("type", "string").put("description", "CloudEvent type exact match");
    ObjectNode objVariant = oneOf.addObject();
    objVariant.put("type", "object");
    objVariant.putArray("required").add("type");
    objVariant.put("unevaluatedProperties", false);
    objVariant.put("additionalProperties", false);
    ObjectNode props = objVariant.putObject("properties");
    props.putObject("type").put("type", "string");
    props.putObject("source").put("type", "string");
    props.putObject("subject").put("type", "string");
    props.putObject("filter").put("$ref", "#/$defs/ExpressionOrOverride");
    return n;
  }

  private static ObjectNode buildScheduleTrigger() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("description", "Time-based trigger.");
    n.put("unevaluatedProperties", false);
    n.put("additionalProperties", false);
    ArrayNode oneOf = n.putArray("oneOf");
    oneOf.addObject().putArray("required").add("cron");
    oneOf.addObject().putArray("required").add("every");
    ObjectNode props = n.putObject("properties");
    props.putObject("cron").put("type", "string");
    props.putObject("every").put("type", "string");
    props.putObject("timezone").put("type", "string");
    return n;
  }

  private static ObjectNode buildScopeActivatedTrigger() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("additionalProperties", false);
    return n;
  }

  private static ObjectNode buildContextChangeTrigger() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.put("additionalProperties", false);
    ObjectNode props = n.putObject("properties");
    props.putObject("filter").put("$ref", "#/$defs/ExpressionOrOverride");
    props.putObject("listenLayer").put("type", "string");
    return n;
  }

  private static ObjectNode buildExecutionPolicy() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    ObjectNode props = n.putObject("properties");
    ObjectNode timeout = props.putObject("timeoutMs");
    timeout.put("type", "integer");
    timeout.put("minimum", 60000);
    props.putObject("retries").put("$ref", "#/$defs/RetryPolicy");
    return n;
  }

  private static ObjectNode buildRetryPolicy() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    ObjectNode props = n.putObject("properties");
    ObjectNode maxAttempts = props.putObject("maxAttempts");
    maxAttempts.put("type", "integer");
    maxAttempts.put("minimum", 1);
    maxAttempts.put("default", 3);
    ObjectNode delayMs = props.putObject("delayMs");
    delayMs.put("type", "integer");
    delayMs.put("minimum", 0);
    delayMs.put("default", 100);
    return n;
  }

  private static ObjectNode buildAuthorization() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    ObjectNode props = n.putObject("properties");
    for (String action : List.of("read", "write", "admin", "claim")) {
      ObjectNode arr = props.putObject(action);
      arr.put("type", "array");
      arr.putObject("items").put("type", "string");
    }
    return n;
  }

  private static ObjectNode buildCbr() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.putArray("required").add("features");
    ObjectNode props = n.putObject("properties");
    ObjectNode features = props.putObject("features");
    features.put("type", "object");
    features.putObject("additionalProperties").put("type", "string");
    ObjectNode weights = props.putObject("weights");
    weights.put("type", "object");
    weights.putObject("additionalProperties").put("type", "number");
    ObjectNode topK = props.putObject("topK");
    topK.put("type", "integer");
    topK.put("minimum", 1);
    topK.put("default", 5);
    ObjectNode minSim = props.putObject("minSimilarity");
    minSim.put("type", "number");
    minSim.put("minimum", 0);
    minSim.put("maximum", 1);
    minSim.put("default", 0);
    ObjectNode vw = props.putObject("vectorWeight");
    vw.put("type", "number");
    vw.put("minimum", 0);
    vw.put("maximum", 1);
    vw.put("default", 0.5);
    addStringProp(props, "domain", 1, 0, null);
    addStringProp(props, "caseType", 1, 0, null);
    addStringProp(props, "cbrType", 1, 0, null);
    ObjectNode timing = props.putObject("timing");
    timing.put("type", "string");
    timing.putArray("enum").add("per-evaluation").add("case-lifetime");
    timing.put("default", "per-evaluation");
    ObjectNode decay = props.putObject("temporalDecayHalfLifeDays");
    decay.put("type", "integer");
    decay.put("minimum", 1);
    ObjectNode minCost = props.putObject("minCostSamples");
    minCost.put("type", "integer");
    minCost.put("minimum", 1);
    return n;
  }

  private static ObjectNode buildAgent() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    n.putArray("required")
        .add("systemPrompt")
        .add("inputProjection")
        .add("outputProjection")
        .add("model");
    ObjectNode props = n.putObject("properties");
    props.putObject("systemPrompt").put("type", "string");
    props.putObject("inputProjection").put("type", "string");
    props.putObject("outputProjection").put("type", "string");
    props.putObject("userMessageTemplate").put("type", "string");
    props.putObject("model").put("$ref", "#/$defs/AgentModel");
    return n;
  }

  private static ObjectNode buildAgentModel() {
    ObjectNode n = newObject();
    n.put("type", "object");
    n.put("unevaluatedProperties", false);
    ArrayNode oneOf = n.putArray("oneOf");
    oneOf.addObject().putArray("required").add("openai");
    oneOf.addObject().putArray("required").add("ollama");
    oneOf.addObject().putArray("required").add("anthropic");
    oneOf.addObject().putArray("required").add("mistralAi");
    oneOf.addObject().putArray("required").add("googleAiGemini");
    ObjectNode props = n.putObject("properties");
    props.putObject("openai").put("$ref", "#/$defs/OpenAiModel");
    props.putObject("ollama").put("$ref", "#/$defs/OllamaModel");
    props.putObject("anthropic").put("$ref", "#/$defs/AnthropicModel");
    props.putObject("mistralAi").put("$ref", "#/$defs/MistralAiModel");
    props.putObject("googleAiGemini").put("$ref", "#/$defs/GoogleAiGeminiModel");
    return n;
  }

  private static void buildLlmProviderDefs(ObjectNode defs) {
    Map<String, List<String>> providers =
        Map.of(
            "OpenAiModel",
                List.of(
                    "apiKey",
                    "modelName",
                    "temperature",
                    "maxTokens",
                    "topP",
                    "frequencyPenalty",
                    "presencePenalty",
                    "organizationId",
                    "baseUrl"),
            "AnthropicModel",
                List.of(
                    "apiKey",
                    "modelName",
                    "version",
                    "temperature",
                    "maxTokens",
                    "topP",
                    "topK",
                    "baseUrl"),
            "MistralAiModel",
                List.of("apiKey", "modelName", "temperature", "maxTokens", "topP", "baseUrl"),
            "GoogleAiGeminiModel",
                List.of("apiKey", "modelName", "temperature", "maxTokens", "topP", "topK"),
            "OllamaModel",
                List.of("baseUrl", "modelName", "temperature", "maxTokens", "topP", "topK"));
    Map<String, List<String>> required =
        Map.of(
            "OpenAiModel", List.of("modelName"),
            "AnthropicModel", List.of("modelName"),
            "MistralAiModel", List.of("modelName"),
            "GoogleAiGeminiModel", List.of("modelName"),
            "OllamaModel", List.of("baseUrl", "modelName"));
    Set<String> stringFields =
        Set.of("apiKey", "modelName", "version", "organizationId", "baseUrl");
    Set<String> numberFields = Set.of("temperature", "topP", "frequencyPenalty", "presencePenalty");
    Set<String> intFields = Set.of("maxTokens", "topK");
    for (var entry : providers.entrySet()) {
      ObjectNode n = newObject();
      n.put("type", "object");
      n.put("unevaluatedProperties", false);
      ArrayNode req = n.putArray("required");
      required.get(entry.getKey()).forEach(req::add);
      ObjectNode props = n.putObject("properties");
      for (String field : entry.getValue()) {
        ObjectNode prop = props.putObject(field);
        if (stringFields.contains(field)) {
          prop.put("type", "string");
        } else if (numberFields.contains(field)) {
          prop.put("type", "number");
        } else if (intFields.contains(field)) {
          prop.put("type", "integer");
          prop.put("minimum", 1);
        }
      }
      defs.set(entry.getKey(), n);
    }
  }

  // --- Helpers ---

  private static void addStringProp(
      ObjectNode props, String name, int minLength, int maxLength, String pattern) {
    ObjectNode prop = props.putObject(name);
    prop.put("type", "string");
    if (minLength > 0) {
      prop.put("minLength", minLength);
    }
    if (maxLength > 0) {
      prop.put("maxLength", maxLength);
    }
    if (pattern != null) {
      prop.put("pattern", pattern);
    }
  }
}
