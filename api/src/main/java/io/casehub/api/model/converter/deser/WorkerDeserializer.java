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
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.worker.api.Worker;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public class WorkerDeserializer extends StdDeserializer<Worker> {

  public WorkerDeserializer() {
    super(Worker.class);
  }

  @Override
  public Worker deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode node = p.readValueAsTree();
    if (node == null || node.isNull()) {
      return null;
    }

    String name = node.has("name") ? node.get("name").asText() : null;

    Set<String> capabilities = new LinkedHashSet<>();
    JsonNode capsNode = node.get("capabilities");
    if (capsNode != null && capsNode.isArray()) {
      capsNode.forEach(n -> capabilities.add(n.asText()));
    }

    String description = node.has("description") ? node.get("description").asText() : null;

    ExecutionPolicy executionPolicy = null;
    JsonNode epNode = node.get("executionPolicy");
    if (epNode != null && epNode.isObject()) {
      JsonParser nested = epNode.traverse(ctxt.getParser().getCodec());
      nested.nextToken();
      executionPolicy = ctxt.readValue(nested, ExecutionPolicy.class);
    }

    Worker.Builder builder = Worker.builder().name(name).capabilityNames(capabilities).noFunction();
    if (description != null) {
      builder.description(description);
    }
    if (executionPolicy != null) {
      builder.executionPolicy(executionPolicy);
    }
    return builder.build();
  }

  @Override
  public Worker getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
