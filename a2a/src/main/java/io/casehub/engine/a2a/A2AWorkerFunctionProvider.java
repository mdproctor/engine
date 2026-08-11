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
package io.casehub.engine.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.engine.common.internal.auth.AuthConfig;
import io.casehub.worker.api.WorkerFunction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class A2AWorkerFunctionProvider implements WorkerFunctionProvider {

  private static final Logger LOG = Logger.getLogger(A2AWorkerFunctionProvider.class);

  @jakarta.inject.Inject A2AEndpointRegistry endpointRegistry;

  @Override
  public boolean handles(final JsonNode rawWorkerNode) {
    return rawWorkerNode.has("a2a");
  }

  @Override
  public WorkerFunction<?, ?> create(final JsonNode rawWorkerNode) {
    final String workerName = rawWorkerNode.has("name") ? rawWorkerNode.get("name").asText() : null;
    final JsonNode a2a = rawWorkerNode.get("a2a");
    final String endpoint = a2a.get("endpoint").asText();
    final String skill = a2a.has("skill") ? a2a.get("skill").asText() : null;
    final boolean streaming = a2a.has("streaming") && a2a.get("streaming").asBoolean();
    final AuthConfig auth = parseAuth(a2a);
    final A2AWorkerFunction function = new A2AWorkerFunction(endpoint, skill, streaming, auth);
    if (workerName != null) {
      endpointRegistry.register(workerName, function);
      validateAgentCard(workerName, function, parseCapabilities(rawWorkerNode));
    }
    return function;
  }

  private void validateAgentCard(
      String workerName, A2AWorkerFunction function, Set<String> declaredCapabilities) {
    try (A2AClient client = new A2AClient(function.endpoint(), function.auth())) {
      AgentCard card = client.fetchAgentCard();
      if (card == null) {
        LOG.warnf(
            "A2A worker '%s': agent card unreachable at %s — capability validation skipped",
            workerName, function.endpoint());
        return;
      }
      endpointRegistry.registerAgentCard(workerName, card);
      for (String cap : declaredCapabilities) {
        if (!card.hasSkill(cap)) {
          LOG.warnf(
              "A2A worker '%s': declared capability '%s' not found in agent card at %s",
              workerName, cap, function.endpoint());
        }
      }
    } catch (Exception e) {
      LOG.debugf("A2A worker '%s': agent card fetch failed — %s", workerName, e.getMessage());
    }
  }

  private Set<String> parseCapabilities(JsonNode rawWorkerNode) {
    if (!rawWorkerNode.has("capabilities") || rawWorkerNode.get("capabilities").isEmpty()) {
      return Set.of();
    }
    Set<String> caps = new java.util.LinkedHashSet<>();
    rawWorkerNode.get("capabilities").forEach(n -> caps.add(n.asText()));
    return caps;
  }

  private AuthConfig parseAuth(final JsonNode a2a) {
    if (!a2a.has("auth")) {
      return AuthConfig.NONE;
    }
    final JsonNode authNode = a2a.get("auth");
    final String typeStr = authNode.has("type") ? authNode.get("type").asText("none") : "none";
    final AuthConfig.AuthType type =
        switch (typeStr.toLowerCase()) {
          case "bearer" -> AuthConfig.AuthType.BEARER;
          case "api-key", "api_key" -> AuthConfig.AuthType.API_KEY;
          default -> AuthConfig.AuthType.NONE;
        };
    final String tokenConfigKey =
        authNode.has("tokenConfigKey") ? authNode.get("tokenConfigKey").asText() : null;
    return new AuthConfig(type, tokenConfigKey);
  }
}
