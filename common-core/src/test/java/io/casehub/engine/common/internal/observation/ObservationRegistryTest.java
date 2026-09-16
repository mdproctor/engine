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
package io.casehub.engine.common.internal.observation;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObservationRegistryTest {

  private ObservationRegistry registry;
  private final UUID caseId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    registry = new ObservationRegistry();
  }

  @Test
  void registerAndRetrieveObserver() {
    var observer = testObserver("test-type", Set.of("key1"));
    assertNotNull(registry.registerObserver(caseId, "agent-1", "binding-1", observer, 20));
    assertEquals(1, registry.observerCount(caseId));
    var observers = registry.getObservers(caseId);
    assertEquals(1, observers.get("agent-1").size());
  }

  @Test
  void rejectsWhenCapReached() {
    for (int i = 0; i < 3; i++) {
      registry.registerObserver(
          caseId, "agent-" + i, "binding-" + i, testObserver("type", Set.of()), 3);
    }
    assertNull(
        registry.registerObserver(
            caseId, "agent-4", "binding-4", testObserver("type", Set.of()), 3));
  }

  @Test
  void rejectsNullObserver() {
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.registerObserver(caseId, "a", "b", null, 20));
  }

  @Test
  void rejectsNullObserverType() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.registerObserver(
                caseId,
                "a",
                "b",
                new EnvironmentObserver() {
                  @Override
                  public String observerType() {
                    return null;
                  }

                  @Override
                  public Set<String> watchedKeys() {
                    return Set.of();
                  }

                  @Override
                  public List<Observation> observe(ObservationContext ctx) {
                    return List.of();
                  }
                },
                20));
  }

  @Test
  void unregisterByBinding() {
    registry.registerObserver(caseId, "agent-1", "binding-a", testObserver("t", Set.of()), 20);
    registry.registerObserver(caseId, "agent-2", "binding-b", testObserver("t", Set.of()), 20);
    registry.unregisterByBinding(caseId, Set.of("binding-a"));
    assertEquals(1, registry.observerCount(caseId));
  }

  @Test
  void unregisterByAgent() {
    registry.registerObserver(caseId, "agent-1", "binding-a", testObserver("t", Set.of()), 20);
    registry.registerObserver(caseId, "agent-1", "binding-b", testObserver("t", Set.of()), 20);
    registry.registerObserver(caseId, "agent-2", "binding-c", testObserver("t", Set.of()), 20);
    registry.unregisterByAgent(caseId, "agent-1");
    assertEquals(1, registry.observerCount(caseId));
  }

  @Test
  void unregisterByCase() {
    registry.registerObserver(caseId, "agent-1", "binding-a", testObserver("t", Set.of()), 20);
    registry.storeObservations(
        caseId, "agent-1", List.of(new Observation("p", 1.0, Map.of(), Instant.now())));
    registry.unregisterByCase(caseId);
    assertEquals(0, registry.observerCount(caseId));
    assertTrue(registry.getObservations(caseId, "agent-1").isEmpty());
  }

  @Test
  void storeAndRetrieveObservations() {
    var obs = new Observation("pattern-1", 0.9, Map.of(), Instant.now());
    registry.storeObservations(caseId, "agent-1", List.of(obs));
    var result = registry.getObservations(caseId, "agent-1");
    assertEquals(1, result.size());
    assertEquals("pattern-1", result.get(0).patternId());
  }

  @Test
  void storeObservationsReplacesExisting() {
    registry.storeObservations(
        caseId, "agent-1", List.of(new Observation("old", 1.0, Map.of(), Instant.now())));
    registry.storeObservations(
        caseId, "agent-1", List.of(new Observation("new", 0.5, Map.of(), Instant.now())));
    var result = registry.getObservations(caseId, "agent-1");
    assertEquals(1, result.size());
    assertEquals("new", result.get(0).patternId());
  }

  @Test
  void getObserversReturnsEmptyForUnknownCase() {
    assertTrue(registry.getObservers(UUID.randomUUID()).isEmpty());
  }

  @Test
  void getObservationsReturnsEmptyForUnknownAgent() {
    assertTrue(registry.getObservations(caseId, "nonexistent").isEmpty());
  }

  @Test
  void resetClearsEverything() {
    registry.registerObserver(caseId, "agent-1", "b-1", testObserver("t", Set.of()), 20);
    registry.storeObservations(
        caseId, "agent-1", List.of(new Observation("p", 1.0, Map.of(), Instant.now())));
    registry.reset();
    assertEquals(0, registry.observerCount(caseId));
    assertTrue(registry.getObservations(caseId, "agent-1").isEmpty());
  }

  private EnvironmentObserver testObserver(String type, Set<String> keys) {
    return new EnvironmentObserver() {
      @Override
      public String observerType() {
        return type;
      }

      @Override
      public Set<String> watchedKeys() {
        return keys;
      }

      @Override
      public List<Observation> observe(ObservationContext ctx) {
        return List.of();
      }
    };
  }
}
