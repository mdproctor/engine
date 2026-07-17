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
package io.casehub.api.model.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

/**
 * Renames deprecated YAML field names in a raw {@link JsonNode} tree before typed deserialization.
 * Each rename emits a DEPRECATION warning so stale configs surface at load time.
 */
final class DeprecatedFieldRenamer {

  private static final Logger LOG = Logger.getLogger(DeprecatedFieldRenamer.class);

  private DeprecatedFieldRenamer() {}

  static int apply(JsonNode rawNode) {
    int count = 0;
    JsonNode spec = rawNode.path("spec");
    if (spec.isMissingNode()) {
      return 0;
    }

    JsonNode capabilities = spec.path("capabilities");
    if (capabilities.isArray()) {
      for (JsonNode cap : capabilities) {
        if (cap.isObject()) {
          count +=
              renameField(
                  (ObjectNode) cap,
                  "inputSchema",
                  "inputProjection",
                  "spec.capabilities[].inputSchema");
          count +=
              renameField(
                  (ObjectNode) cap,
                  "outputSchema",
                  "outputProjection",
                  "spec.capabilities[].outputSchema");
        }
      }
    }

    JsonNode workers = spec.path("workers");
    if (workers.isArray()) {
      for (JsonNode worker : workers) {
        if (worker.isObject()) {
          JsonNode agent = worker.path("agent");
          if (agent.isObject()) {
            count +=
                renameField(
                    (ObjectNode) agent,
                    "inputSchema",
                    "inputProjection",
                    "spec.workers[].agent.inputSchema");
            count +=
                renameField(
                    (ObjectNode) agent,
                    "outputSchema",
                    "outputProjection",
                    "spec.workers[].agent.outputSchema");
          }
        }
      }
    }
    return count;
  }

  private static int renameField(ObjectNode node, String oldName, String newName, String path) {
    if (!node.has(oldName)) {
      return 0;
    }
    if (node.has(newName)) {
      LOG.warnf(
          "DEPRECATED: YAML field '%s' has been renamed to '%s' at %s"
              + " — both present, using '%s' and ignoring deprecated '%s'",
          oldName, newName, path, newName, oldName);
    } else {
      LOG.warnf(
          "DEPRECATED: YAML field '%s' has been renamed to '%s' at %s"
              + " — please update your case definition YAML."
              + " The old name will be removed in a future release.",
          oldName, newName, path);
      node.set(newName, node.get(oldName));
    }
    node.remove(oldName);
    return 1;
  }
}
