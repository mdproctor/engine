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
package io.casehub.engine.mcp;

import io.casehub.engine.common.internal.auth.AuthConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface McpTransport {

  record Stdio(List<String> command, Map<String, String> env) implements McpTransport {
    public Stdio {
      Objects.requireNonNull(command);
      if (command.isEmpty()) {
        throw new IllegalArgumentException("command must not be empty");
      }
      command = List.copyOf(command);
      env = env != null ? Map.copyOf(env) : Map.of();
    }
  }

  record Http(String url, AuthConfig auth) implements McpTransport {
    public Http {
      Objects.requireNonNull(url);
      auth = auth != null ? auth : AuthConfig.NONE;
    }
  }
}
