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
package io.casehub.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.Worker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class YamlCaseHubTest {

  @Test
  void augment_calledDuringGetDefinition() {
    var hub = new AugmentingHub("casehub/minimal.yaml");
    wireForTest(hub);

    CaseDefinition def = hub.getDefinition();

    assertThat(def.getWorkers()).hasSize(1);
    assertThat(def.getWorkers().get(0).name()).isEqualTo("test-worker");
    assertThat(def.getWorkers().get(0).capabilityNames()).containsExactly("process");
  }

  @Test
  void getDefinition_caches_augmentCalledOnce() {
    var hub = new CountingHub("casehub/minimal.yaml");
    wireForTest(hub);

    hub.getDefinition();
    hub.getDefinition();
    hub.getDefinition();

    assertThat(hub.augmentCount.get()).isEqualTo(1);
  }

  @Test
  void noAugment_returnsYamlDefinitionUnchanged() {
    var hub = new PlainHub("casehub/minimal.yaml");
    wireForTest(hub);

    CaseDefinition def = hub.getDefinition();

    assertThat(def.getName()).isEqualTo("Minimal");
    assertThat(def.getNamespace()).isEqualTo("test");
    assertThat(def.getCapabilities()).hasSize(1);
    assertThat(def.getWorkers()).isEmpty();
  }

  private static void wireForTest(YamlCaseHub hub) {
    hub.objectMapper = new ObjectMapper(new YAMLFactory());
    hub.expressionEngineRegistry = new JqOnlyExpressionEngineRegistry();
    hub.workerFunctionProviderRegistry = rawWorkerNode -> null;
  }

  static class AugmentingHub extends YamlCaseHub {
    AugmentingHub(String path) {
      super(path);
    }

    @Override
    protected void augment(CaseDefinition definition) {
      definition
          .getWorkers()
          .add(
              Worker.builder()
                  .name("test-worker")
                  .capabilityName("process")
                  .function(input -> io.casehub.worker.api.WorkerResult.of(java.util.Map.of()))
                  .build());
    }
  }

  static class CountingHub extends YamlCaseHub {
    final AtomicInteger augmentCount = new AtomicInteger();

    CountingHub(String path) {
      super(path);
    }

    @Override
    protected void augment(CaseDefinition definition) {
      augmentCount.incrementAndGet();
    }
  }

  static class PlainHub extends YamlCaseHub {
    PlainHub(String path) {
      super(path);
    }
  }
}
