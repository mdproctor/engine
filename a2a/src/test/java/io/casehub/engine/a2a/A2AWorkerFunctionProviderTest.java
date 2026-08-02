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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

class A2AWorkerFunctionProviderTest {

  private final A2AWorkerFunctionProvider provider = new A2AWorkerFunctionProvider();
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  @Test
  void handlesReturnsTrueForA2aBlock() throws Exception {
    var node =
        yaml.readTree(
            """
            name: remote-analyst
            capabilities: [analysis]
            a2a:
              endpoint: https://example.com
            """);
    assertThat(provider.handles(node)).isTrue();
  }

  @Test
  void handlesReturnsFalseWithoutA2aBlock() throws Exception {
    var node =
        yaml.readTree(
            """
            name: local-worker
            capabilities: [analysis]
            """);
    assertThat(provider.handles(node)).isFalse();
  }

  @Test
  void createParsesAllFields() throws Exception {
    var node =
        yaml.readTree(
            """
            name: remote-analyst
            a2a:
              endpoint: https://example.com
              skill: anomaly-detection
              streaming: true
              auth:
                type: bearer
                tokenConfigKey: analyst.token
            """);
    var fn = (A2AWorkerFunction) provider.create(node);

    assertThat(fn.endpoint()).isEqualTo("https://example.com");
    assertThat(fn.skill()).isEqualTo("anomaly-detection");
    assertThat(fn.streaming()).isTrue();
    assertThat(fn.auth().type()).isEqualTo(A2AAuthConfig.AuthType.BEARER);
    assertThat(fn.auth().tokenConfigKey()).isEqualTo("analyst.token");
  }

  @Test
  void createUsesDefaultsForOptionalFields() throws Exception {
    var node =
        yaml.readTree(
            """
            name: remote-analyst
            a2a:
              endpoint: https://example.com
            """);
    var fn = (A2AWorkerFunction) provider.create(node);

    assertThat(fn.endpoint()).isEqualTo("https://example.com");
    assertThat(fn.skill()).isNull();
    assertThat(fn.streaming()).isFalse();
    assertThat(fn.auth()).isEqualTo(A2AAuthConfig.NONE);
  }

  @Test
  void createParsesApiKeyAuth() throws Exception {
    var node =
        yaml.readTree(
            """
            name: remote-analyst
            a2a:
              endpoint: https://example.com
              auth:
                type: api-key
                tokenConfigKey: analyst.api-key
            """);
    var fn = (A2AWorkerFunction) provider.create(node);

    assertThat(fn.auth().type()).isEqualTo(A2AAuthConfig.AuthType.API_KEY);
    assertThat(fn.auth().tokenConfigKey()).isEqualTo("analyst.api-key");
  }

  @Test
  void inputOutputTypesAreMap() throws Exception {
    var node =
        yaml.readTree(
            """
            name: remote-analyst
            a2a:
              endpoint: https://example.com
            """);
    var fn = (A2AWorkerFunction) provider.create(node);

    assertThat(fn.inputType()).isEqualTo(java.util.Map.class);
    assertThat(fn.outputType()).isEqualTo(java.util.Map.class);
  }
}
