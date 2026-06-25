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
package io.casehub.engine.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultWorkerFunctionProviderRegistryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void returns_null_when_no_provider_handles() {
    WorkerFunctionProviderRegistry registry = new DefaultWorkerFunctionProviderRegistry(List.of());
    ObjectNode node = MAPPER.createObjectNode().put("name", "test");
    assertThat(registry.createFunction(node)).isNull();
  }

  @Test
  void delegates_to_matching_provider() {
    WorkerFunctionProvider provider =
        new WorkerFunctionProvider() {
          @Override
          public boolean handles(JsonNode raw) {
            return raw.has("do");
          }

          @Override
          public WorkerFunction create(JsonNode raw) {
            return new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("flow", true)));
          }
        };

    WorkerFunctionProviderRegistry registry =
        new DefaultWorkerFunctionProviderRegistry(List.of(provider));

    ObjectNode node = MAPPER.createObjectNode();
    node.putArray("do");

    WorkerFunction fn = registry.createFunction(node);
    assertThat(fn).isNotNull().isInstanceOf(WorkerFunction.Sync.class);
  }

  @Test
  void returns_first_matching_provider() {
    boolean[] firstCalled = {false};
    boolean[] secondCalled = {false};

    WorkerFunctionProvider first =
        new WorkerFunctionProvider() {
          @Override
          public boolean handles(JsonNode raw) {
            return raw.has("type");
          }

          @Override
          public WorkerFunction create(JsonNode raw) {
            firstCalled[0] = true;
            return new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("first", true)));
          }
        };

    WorkerFunctionProvider second =
        new WorkerFunctionProvider() {
          @Override
          public boolean handles(JsonNode raw) {
            return raw.has("type");
          }

          @Override
          public WorkerFunction create(JsonNode raw) {
            secondCalled[0] = true;
            return new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("second", true)));
          }
        };

    WorkerFunctionProviderRegistry registry =
        new DefaultWorkerFunctionProviderRegistry(List.of(first, second));

    ObjectNode node = MAPPER.createObjectNode().put("type", "test");

    WorkerFunction fn = registry.createFunction(node);
    assertThat(fn).isNotNull();
    assertThat(firstCalled[0]).isTrue();
    assertThat(secondCalled[0]).isFalse();
  }
}
