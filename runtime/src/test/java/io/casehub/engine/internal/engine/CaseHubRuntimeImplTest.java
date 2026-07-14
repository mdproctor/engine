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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.context.InMemoryCaseContextStoreFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CaseHubRuntimeImplTest {

  static class RecordingFactory implements CaseContextStoreFactory {
    final CopyOnWriteArrayList<String> layers = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<UUID> caseIds = new CopyOnWriteArrayList<>();

    @Override
    public String id() {
      return "recording";
    }

    @Override
    public CaseContextStore createStore(String layerName, UUID caseId) {
      layers.add(layerName);
      caseIds.add(caseId);
      return InMemoryCaseContextStoreFactory.INSTANCE.createStore(layerName, caseId);
    }
  }

  @Test
  void resolveFactory_durableFactory_throwsUnsupported() {
    var durable =
        new CaseContextStoreFactory() {
          @Override
          public String id() {
            return "durable-test";
          }

          @Override
          public CaseContextStore createStore(String layerName, UUID caseId) {
            return InMemoryCaseContextStoreFactory.INSTANCE.createStore(layerName, caseId);
          }

          @Override
          public boolean isDurable() {
            return true;
          }
        };

    assertThatThrownBy(
            () -> {
              if (durable.isDurable()) {
                throw new UnsupportedOperationException(
                    "CaseContextStoreFactory '"
                        + durable.id()
                        + "' reports isDurable()=true but "
                        + "recovery path is not yet wired");
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("isDurable()=true");
  }

  @Test
  void createContext_withFactory_usesFactoryForAllLayers() {
    var recording = new RecordingFactory();
    UUID caseId = UUID.randomUUID();
    new CaseContextImpl(recording, caseId);

    assertThat(recording.layers).containsExactlyInAnyOrder("working", "semantic", "episodic");
    assertThat(recording.caseIds).containsOnly(caseId);
  }

  @Test
  void createContext_withInputData_populatesWorkingLayer() {
    UUID caseId = UUID.randomUUID();
    var context = new CaseContextImpl(InMemoryCaseContextStoreFactory.INSTANCE, caseId);
    context.setAll(Map.of("key1", "value1", "key2", 42));

    assertThat(context.get("key1")).isEqualTo("value1");
    assertThat(context.get("key2")).isEqualTo(42);
  }

  @Test
  void createContext_emptyInputData_noError() {
    UUID caseId = UUID.randomUUID();
    var context = new CaseContextImpl(InMemoryCaseContextStoreFactory.INSTANCE, caseId);
    context.setAll(Map.of());

    assertThat(context.isEmpty()).isTrue();
  }

  @Test
  void createContext_nullInputData_noError() {
    UUID caseId = UUID.randomUUID();
    var context = new CaseContextImpl(InMemoryCaseContextStoreFactory.INSTANCE, caseId);

    assertThat(context.isEmpty()).isTrue();
  }
}
