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

@ApplicationScoped
public class A2AWorkerFunctionProvider implements WorkerFunctionProvider {

  @Override
  public boolean handles(final JsonNode rawWorkerNode) {
    return rawWorkerNode.has("a2a");
  }

  @Override
  public WorkerFunction<?, ?> create(final JsonNode rawWorkerNode) {
    final JsonNode a2a = rawWorkerNode.get("a2a");
    final String endpoint = a2a.get("endpoint").asText();
    final String skill = a2a.has("skill") ? a2a.get("skill").asText() : null;
    final boolean streaming = a2a.has("streaming") && a2a.get("streaming").asBoolean();
    final AuthConfig auth = parseAuth(a2a);
    return new A2AWorkerFunction(endpoint, skill, streaming, auth);
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
