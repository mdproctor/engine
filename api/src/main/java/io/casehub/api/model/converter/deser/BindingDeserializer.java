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
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.model.OutcomeType;
import io.casehub.api.model.Participation;
import io.casehub.api.model.RecoveryLevel;
import io.casehub.api.model.RecoveryOverride;
import io.casehub.api.model.ReplanHint;
import io.casehub.api.model.SideEffectClassification;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.SubCaseMapping;
import io.casehub.api.model.Trigger;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.Capability;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BindingDeserializer extends StdDeserializer<Binding> {

  static final String CAPABILITY_TARGET_MAP_KEY = "casehub.capabilityTargetMap";

  public BindingDeserializer() {
    super(Binding.class);
  }

  @Override
  public Binding deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    ObjectCodec codec = p.getCodec();
    JsonNode node = p.readValueAsTree();
    if (node == null || node.isNull()) {
      return null;
    }

    String name = textOrNull(node, "name");

    Trigger trigger = null;
    if (node.has("on")) {
      trigger = readValue(node.get("on"), Trigger.class, codec, ctxt);
    }

    Binding.Builder builder = Binding.builder().name(name).on(trigger);

    resolveTarget(node, builder, codec, ctxt);

    if (node.has("when")) {
      builder.when(readValue(node.get("when"), ExpressionEvaluator.class, codec, ctxt));
    }
    if (node.has("inputProjectionOverride")) {
      builder.inputProjectionOverride(
          readValue(node.get("inputProjectionOverride"), ExpressionEvaluator.class, codec, ctxt));
    }

    if (node.has("conflictResolverStrategy")) {
      builder.conflictResolverStrategy(node.get("conflictResolverStrategy").asText());
    }
    if (node.has("lifecycleScope")) {
      builder.lifecycleScope(LifecycleScope.valueOf(node.get("lifecycleScope").asText()));
    }
    if (node.has("participation")) {
      builder.participation(Participation.valueOf(node.get("participation").asText()));
    }
    if (node.has("executionMode")) {
      builder.executionMode(ExecutionMode.valueOf(node.get("executionMode").asText()));
    }
    if (node.has("replanHint")) {
      builder.replanHint(ReplanHint.valueOf(node.get("replanHint").asText().toUpperCase()));
    } else if (node.has("replanAfter")) {
      builder.replanHint(ReplanHint.valueOf(node.get("replanAfter").asText().toUpperCase()));
    }

    if (node.has("outcomePolicy")) {
      builder.outcomePolicy(deserializeOutcomePolicy(node.get("outcomePolicy")));
    }

    if (node.has("contextWrite")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cw =
          ((ObjectMapper) codec).convertValue(node.get("contextWrite"), Map.class);
      if (cw != null && !cw.isEmpty()) {
        builder.contextWrite(cw);
      }
    }

    if (node.has("producedKeys")) {
      Set<String> keys = new LinkedHashSet<>();
      node.get("producedKeys").forEach(n -> keys.add(n.asText()));
      builder.producedKeys(keys);
    }

    if (node.has("contingency")) {
      List<String> cont = new ArrayList<>();
      node.get("contingency").forEach(n -> cont.add(n.asText()));
      builder.contingency(cont);
    }

    if (node.has("exchangeProjectionStrategy")) {
      builder.exchangeProjectionStrategy(node.get("exchangeProjectionStrategy").asText());
    }
    if (node.has("produces")) {
      builder.produces(node.get("produces").asText());
    }
    if (node.has("consumes")) {
      builder.consumes(node.get("consumes").asText());
    }

    if (node.has("sideEffectClassification")) {
      builder.sideEffectClassification(
          SideEffectClassification.valueOf(node.get("sideEffectClassification").asText()));
    }

    if (node.has("recoveryOverride")) {
      builder.recoveryOverride(deserializeRecoveryOverride(node.get("recoveryOverride")));
    }

    return builder.build();
  }

  private void resolveTarget(
      JsonNode node, Binding.Builder builder, ObjectCodec codec, DeserializationContext ctxt)
      throws IOException {
    if (node.has("capability")) {
      String capName = node.get("capability").asText();
      @SuppressWarnings("unchecked")
      Map<String, CapabilityTarget> capTargetMap =
          (Map<String, CapabilityTarget>) ctxt.getAttribute(CAPABILITY_TARGET_MAP_KEY);
      if (capTargetMap != null && capTargetMap.containsKey(capName)) {
        builder.target(capTargetMap.get(capName));
      } else {
        builder.capability(Capability.of(capName, ".", "."));
      }
    } else if (node.has("subCase")) {
      builder.subCase(deserializeSubCase(node.get("subCase"), codec, ctxt));
    } else if (node.has("humanTask")) {
      builder.humanTask(deserializeHumanTask(node.get("humanTask"), codec, ctxt));
    } else if (node.has("signal")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload =
          ((ObjectMapper) codec).convertValue(node.get("signal"), Map.class);
      builder.signal(payload);
    }
  }

  private SubCase deserializeSubCase(JsonNode node, ObjectCodec codec, DeserializationContext ctxt)
      throws IOException {
    SubCase.Builder b = SubCase.builder();
    if (node.has("namespace")) b.namespace(node.get("namespace").asText());
    if (node.has("name")) b.name(node.get("name").asText());
    if (node.has("version")) b.version(node.get("version").asText());
    if (node.has("waitForCompletion"))
      b.waitForCompletion(node.get("waitForCompletion").asBoolean());
    if (node.has("maxRecursionDepth")) b.maxRecursionDepth(node.get("maxRecursionDepth").asInt());
    if (node.has("inputMapping")) {
      b.inputMapping(readValue(node.get("inputMapping"), SubCaseMapping.class, codec, ctxt));
    }
    if (node.has("outputMapping")) {
      b.outputMapping(readValue(node.get("outputMapping"), SubCaseMapping.class, codec, ctxt));
    }
    if (node.has("groupId")) b.groupId(node.get("groupId").asText());
    if (node.has("totalInGroup")) b.totalInGroup(node.get("totalInGroup").asInt());
    if (node.has("requiredCount")) b.requiredCount(node.get("requiredCount").asInt());
    if (node.has("onThresholdReached")) {
      b.onThresholdReached(OnThresholdReached.valueOf(node.get("onThresholdReached").asText()));
    }
    return b.build();
  }

  private HumanTaskTarget deserializeHumanTask(
      JsonNode node, ObjectCodec codec, DeserializationContext ctxt) throws IOException {
    HumanTaskTarget.Builder b;
    if (node.has("templateRef")) {
      b = HumanTaskTarget.template(node.get("templateRef").asText());
    } else {
      b = HumanTaskTarget.inline();
    }
    if (node.has("title")) b.title(node.get("title").asText());
    if (node.has("candidateGroups")) {
      JsonNode cg = node.get("candidateGroups");
      if (cg.isArray()) {
        Set<String> groups = new LinkedHashSet<>();
        cg.forEach(n -> groups.add(n.asText()));
        b.candidateGroups(groups);
      }
    }
    if (node.has("candidateUsers")) {
      JsonNode cu = node.get("candidateUsers");
      if (cu.isArray()) {
        Set<String> users = new LinkedHashSet<>();
        cu.forEach(n -> users.add(n.asText()));
        b.candidateUsers(users);
      }
    }
    if (node.has("expiresIn")) {
      b.expiresIn(java.time.Duration.parse(node.get("expiresIn").asText()));
    }
    if (node.has("priority")) b.priority(node.get("priority").asText());
    if (node.has("inputMapping")) {
      b.inputMapping(readValue(node.get("inputMapping"), ExpressionEvaluator.class, codec, ctxt));
    }
    if (node.has("outputMapping")) {
      b.outputMapping(readValue(node.get("outputMapping"), ExpressionEvaluator.class, codec, ctxt));
    }
    if (node.has("scope")) b.scope(node.get("scope").asText());
    if (node.has("outcomes")) {
      Set<String> outcomes = new LinkedHashSet<>();
      node.get("outcomes").forEach(n -> outcomes.add(n.asText()));
      b.outcomes(outcomes);
    }
    return b.build();
  }

  private OutcomePolicy deserializeOutcomePolicy(JsonNode node) {
    OutcomeAction onDecline =
        node.has("onDecline")
            ? OutcomeAction.valueOf(node.get("onDecline").asText())
            : OutcomeAction.REROUTE;
    OutcomeAction onFailure =
        node.has("onFailure")
            ? OutcomeAction.valueOf(node.get("onFailure").asText())
            : OutcomeAction.REROUTE;
    OutcomeAction onExpired =
        node.has("onExpired")
            ? OutcomeAction.valueOf(node.get("onExpired").asText())
            : OutcomeAction.REROUTE;
    int maxAttempts = node.has("maxRerouteAttempts") ? node.get("maxRerouteAttempts").asInt() : 3;
    return new OutcomePolicy(onDecline, onFailure, onExpired, maxAttempts);
  }

  private RecoveryOverride deserializeRecoveryOverride(JsonNode node) {
    Set<OutcomeType> skipFor = new HashSet<>();
    if (node.has("skipRecoveryFor")) {
      node.get("skipRecoveryFor").forEach(n -> skipFor.add(OutcomeType.valueOf(n.asText())));
    }
    return new RecoveryOverride(
        node.has("maxRetries") ? node.get("maxRetries").asInt() : null,
        node.has("maxRerouteAttempts") ? node.get("maxRerouteAttempts").asInt() : null,
        node.has("maxLevel") ? RecoveryLevel.valueOf(node.get("maxLevel").asText()) : null,
        node.has("skipRecovery") && node.get("skipRecovery").asBoolean(),
        skipFor);
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

  @Override
  public Binding getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
