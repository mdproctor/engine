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
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.ScopeActivatedTrigger;
import io.casehub.api.model.Trigger;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.io.IOException;

public class TriggerDeserializer extends StdDeserializer<Trigger> {

  private static final String[] VALID_KEYS = {"contextChange", "schedule", "scopeActivated"};

  public TriggerDeserializer() {
    super(Trigger.class);
  }

  @Override
  public Trigger deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode node = p.readValueAsTree();
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject() || node.isEmpty()) {
      throw ctxt.weirdStringException(
          node.toString(), Trigger.class, "Trigger must be an object with exactly one key");
    }
    if (node.size() != 1) {
      throw ctxt.weirdStringException(
          node.toString(),
          Trigger.class,
          "Trigger must have exactly one key, got "
              + node.size()
              + ". Valid keys: contextChange, schedule, scopeActivated");
    }
    String key = node.fieldNames().next();
    JsonNode value = node.get(key);

    return switch (key) {
      case "contextChange" -> deserializeContextChange(value, ctxt);
      case "schedule" -> deserializeSchedule(value, ctxt);
      case "scopeActivated" -> new ScopeActivatedTrigger();
      default ->
          throw ctxt.weirdStringException(
              key,
              Trigger.class,
              "Unknown trigger type: '"
                  + key
                  + "'. Valid types: contextChange, schedule, scopeActivated");
    };
  }

  private Trigger deserializeContextChange(JsonNode value, DeserializationContext ctxt)
      throws IOException {
    ExpressionEvaluator filter = null;
    String listenLayer = null;
    if (value != null && value.isObject()) {
      JsonNode filterNode = value.get("filter");
      if (filterNode != null && filterNode.isTextual()) {
        String defaultLang =
            (String) ctxt.getAttribute(ExpressionEvaluatorDeserializer.EXPRESSION_LANG_KEY);
        if (defaultLang == null || JQExpressionEvaluator.TYPE.equals(defaultLang)) {
          filter = new JQExpressionEvaluator(filterNode.asText());
        } else {
          JsonParser nested = filterNode.traverse(ctxt.getParser().getCodec());
          nested.nextToken();
          filter = ctxt.readValue(nested, ExpressionEvaluator.class);
        }
      }
      JsonNode listenLayerNode = value.get("listenLayer");
      if (listenLayerNode != null && listenLayerNode.isTextual()) {
        listenLayer = listenLayerNode.asText();
      }
    }
    return new ContextChangeTrigger(filter, listenLayer);
  }

  private Trigger deserializeSchedule(JsonNode value, DeserializationContext ctxt)
      throws IOException {
    if (value == null || !value.isObject()) {
      throw ctxt.weirdStringException(
          String.valueOf(value),
          Trigger.class,
          "schedule trigger must be an object with 'cron' or 'every'");
    }
    JsonNode cronNode = value.get("cron");
    if (cronNode != null && cronNode.isTextual()) {
      return ScheduleTrigger.cron(cronNode.asText());
    }
    JsonNode everyNode = value.get("every");
    if (everyNode != null && everyNode.isTextual()) {
      return ScheduleTrigger.delay(java.time.Duration.parse(everyNode.asText()));
    }
    throw ctxt.weirdStringException(
        value.toString(), Trigger.class, "ScheduleTrigger must have either 'cron' or 'every' set");
  }

  @Override
  public Trigger getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
