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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.engine.common.internal.auth.AuthConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpWorkerFunctionTest {

  @Test
  void stdioTransportRequiresNonEmptyCommand() {
    assertThatThrownBy(() -> new McpTransport.Stdio(List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stdioTransportDefaultsEnvToEmptyMap() {
    var transport = new McpTransport.Stdio(List.of("/path/to/server"), null);
    assertThat(transport.env()).isEmpty();
  }

  @Test
  void stdioTransportCopiesCommand() {
    var cmd = new java.util.ArrayList<>(List.of("/bin/server"));
    var transport = new McpTransport.Stdio(cmd, Map.of());
    cmd.add("--modified");
    assertThat(transport.command()).hasSize(1);
  }

  @Test
  void httpTransportDefaultsAuthToNone() {
    var transport = new McpTransport.Http("https://example.com/mcp", null);
    assertThat(transport.auth()).isEqualTo(AuthConfig.NONE);
  }

  @Test
  void httpTransportRequiresUrl() {
    assertThatThrownBy(() -> new McpTransport.Http(null, AuthConfig.NONE))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void functionCarriesTransportAndToolName() {
    var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
    var fn = new McpWorkerFunction(transport, "read-file");
    assertThat(fn.transport()).isEqualTo(transport);
    assertThat(fn.toolName()).isEqualTo("read-file");
    assertThat(fn.inputType()).isEqualTo(Map.class);
    assertThat(fn.outputType()).isEqualTo(Map.class);
  }

  @Test
  void functionRequiresToolName() {
    var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
    assertThatThrownBy(() -> new McpWorkerFunction(transport, null))
        .isInstanceOf(NullPointerException.class);
  }
}
