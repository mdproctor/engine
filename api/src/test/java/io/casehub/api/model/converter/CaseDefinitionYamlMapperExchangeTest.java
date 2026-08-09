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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ChannelDeclaration;
import io.casehub.api.model.LifecycleScope;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperExchangeTest {

  @Test
  void parsesProducesAndConsumesOnBinding() {
    String yaml =
        """
                dsl: "0.1.0"
                namespace: test
                name: exchange-test
                version: "1.0.0"
                spec:
                  capabilities:
                    - name: extraction
                    - name: transformation
                  bindings:
                    - name: extract
                      capability: extraction
                      on:
                        contextChange:
                          filter: ".raw != null"
                      produces: tx-stream
                    - name: transform
                      capability: transformation
                      on:
                        contextChange:
                          filter: ".extracted != null"
                      consumes: tx-stream
                  workers:
                    - name: extractor
                      capabilities: [extraction]
                    - name: transformer
                      capabilities: [transformation]
                """;

    CaseDefinition def = load(yaml);
    Binding extract =
        def.getBindings().stream()
            .filter(b -> "extract".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    Binding transform =
        def.getBindings().stream()
            .filter(b -> "transform".equals(b.getName()))
            .findFirst()
            .orElseThrow();

    assertThat(extract.getProduces()).isEqualTo("tx-stream");
    assertThat(extract.getConsumes()).isNull();
    assertThat(transform.getConsumes()).isEqualTo("tx-stream");
    assertThat(transform.getProduces()).isNull();
  }

  @Test
  void parsesExchangeProjectionAsString() {
    String yaml =
        """
                dsl: "0.1.0"
                namespace: test
                name: projection-test
                version: "1.0.0"
                spec:
                  capabilities:
                    - name: enrichment
                  bindings:
                    - name: enricher
                      capability: enrichment
                      on:
                        contextChange:
                          filter: ".raw != null"
                      exchangeProjection: exchange-only
                  workers:
                    - name: enricher-worker
                      capabilities: [enrichment]
                """;

    CaseDefinition def = load(yaml);
    Binding binding = def.getBindings().get(0);

    assertThat(binding.getExchangeProjectionStrategy()).isEqualTo("exchange-only");
  }

  @Test
  void parsesExchangeProjectionAsObject() {
    String yaml =
        """
                dsl: "0.1.0"
                namespace: test
                name: jq-projection-test
                version: "1.0.0"
                spec:
                  capabilities:
                    - name: audit
                  bindings:
                    - name: auditor
                      capability: audit
                      on:
                        contextChange:
                          filter: ".enriched != null"
                      exchangeProjection:
                        strategy: jq
                        expression: "{ auditRecord: .body, source: .headers.sourceSystem }"
                  workers:
                    - name: audit-worker
                      capabilities: [audit]
                """;

    CaseDefinition def = load(yaml);
    Binding binding = def.getBindings().get(0);

    assertThat(binding.getExchangeProjectionStrategy()).isEqualTo("jq");
    assertThat(binding.getExchangeProjectionExpression())
        .isEqualTo("{ auditRecord: .body, source: .headers.sourceSystem }");
  }

  @Test
  void parsesChannelDeclarations() {
    String yaml =
        """
                dsl: "0.1.0"
                namespace: test
                name: channel-test
                version: "1.0.0"
                spec:
                  channels:
                    - name: tx-stream
                      recordType: java.lang.String
                    - name: audit-events
                      recordType: java.util.Map
                      transport: kafka
                      scope: COMPOUND
                  capabilities:
                    - name: processing
                  bindings:
                    - name: processor
                      capability: processing
                      on:
                        contextChange:
                          filter: ".input != null"
                  workers:
                    - name: processor-worker
                      capabilities: [processing]
                """;

    CaseDefinition def = load(yaml);

    assertThat(def.getChannels()).hasSize(2);

    ChannelDeclaration ch1 = def.getChannels().get(0);
    assertThat(ch1.name()).isEqualTo("tx-stream");
    assertThat(ch1.recordType()).isEqualTo(String.class);
    assertThat(ch1.transport()).isEqualTo("in-memory");
    assertThat(ch1.scope()).isEqualTo(LifecycleScope.CASE);

    ChannelDeclaration ch2 = def.getChannels().get(1);
    assertThat(ch2.name()).isEqualTo("audit-events");
    assertThat(ch2.recordType()).isEqualTo(java.util.Map.class);
    assertThat(ch2.transport()).isEqualTo("kafka");
    assertThat(ch2.scope()).isEqualTo(LifecycleScope.COMPOUND);
  }

  @Test
  void noExchangeFieldsDefaultsToNull() {
    String yaml =
        """
                dsl: "0.1.0"
                namespace: test
                name: no-exchange-test
                version: "1.0.0"
                spec:
                  capabilities:
                    - name: basic
                  bindings:
                    - name: basic-binding
                      capability: basic
                      on:
                        contextChange:
                          filter: ".data != null"
                  workers:
                    - name: basic-worker
                      capabilities: [basic]
                """;

    CaseDefinition def = load(yaml);
    Binding binding = def.getBindings().get(0);

    assertThat(binding.getProduces()).isNull();
    assertThat(binding.getConsumes()).isNull();
    assertThat(binding.getExchangeProjectionStrategy()).isNull();
    assertThat(def.getChannels()).isEmpty();
  }

  private static CaseDefinition load(String yaml) {
    try {
      return CaseDefinitionYamlMapper.load(
          new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }
}
