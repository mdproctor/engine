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
package io.casehub.engine.internal.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import io.casehub.api.spi.observation.ContextSnapshot;
import io.casehub.api.spi.observation.ObservationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporalSequenceObserverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void detectsTemporalSequence() {
    var observer =
        TemporalSequenceObserver.of(
            List.of(
                new TemporalSequenceObserver.SequenceStep("alert", null),
                new TemporalSequenceObserver.SequenceStep("escalation", null)),
            Duration.ofMinutes(5));

    var now = Instant.now();
    var history =
        List.of(
            new ContextSnapshot(
                Set.of("alert"), Map.of("alert", IntNode.valueOf(1)), now.minusSeconds(60)),
            new ContextSnapshot(
                Set.of("escalation"),
                Map.of("escalation", IntNode.valueOf(1)),
                now.minusSeconds(30)));

    var snapshot = MAPPER.createObjectNode().put("alert", 1).put("escalation", 1);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("escalation"), history, "agent-1", "t1", UUID.randomUUID());
    var observations = observer.observe(ctx);
    assertEquals(1, observations.size());
    assertEquals("temporal-sequence", observations.get(0).patternId());
  }

  @Test
  void noObservationWhenSequenceIncomplete() {
    var observer =
        TemporalSequenceObserver.of(
            List.of(
                new TemporalSequenceObserver.SequenceStep("alert", null),
                new TemporalSequenceObserver.SequenceStep("escalation", null)),
            Duration.ofMinutes(5));

    var now = Instant.now();
    var history =
        List.of(
            new ContextSnapshot(
                Set.of("alert"), Map.of("alert", IntNode.valueOf(1)), now.minusSeconds(60)));

    var snapshot = MAPPER.createObjectNode().put("alert", 1);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("alert"), history, "agent-1", "t1", UUID.randomUUID());
    assertTrue(observer.observe(ctx).isEmpty());
  }

  @Test
  void noObservationWhenOutsideWindow() {
    var observer =
        TemporalSequenceObserver.of(
            List.of(
                new TemporalSequenceObserver.SequenceStep("alert", null),
                new TemporalSequenceObserver.SequenceStep("escalation", null)),
            Duration.ofSeconds(30));

    var now = Instant.now();
    var history =
        List.of(
            new ContextSnapshot(
                Set.of("alert"), Map.of("alert", IntNode.valueOf(1)), now.minusSeconds(120)),
            new ContextSnapshot(
                Set.of("escalation"),
                Map.of("escalation", IntNode.valueOf(1)),
                now.minusSeconds(5)));

    var snapshot = MAPPER.createObjectNode().put("alert", 1).put("escalation", 1);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("escalation"), history, "agent-1", "t1", UUID.randomUUID());
    assertTrue(observer.observe(ctx).isEmpty());
  }

  @Test
  void watchedKeysIncludesAllSequenceKeys() {
    var observer =
        TemporalSequenceObserver.of(
            List.of(
                new TemporalSequenceObserver.SequenceStep("a", null),
                new TemporalSequenceObserver.SequenceStep("b", null)),
            Duration.ofMinutes(5));
    assertEquals(Set.of("a", "b"), observer.watchedKeys());
  }

  @Test
  void observerTypeIsTemporalSequence() {
    var observer =
        TemporalSequenceObserver.of(
            List.of(
                new TemporalSequenceObserver.SequenceStep("a", null),
                new TemporalSequenceObserver.SequenceStep("b", null)),
            Duration.ofMinutes(5));
    assertEquals("temporal-sequence", observer.observerType());
  }

  @Test
  void rejectsLessThanTwoSteps() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TemporalSequenceObserver.of(
                List.of(new TemporalSequenceObserver.SequenceStep("a", null)),
                Duration.ofMinutes(5)));
  }

  @Test
  void emptyHistoryProducesNoObservation() {
    var observer =
        TemporalSequenceObserver.of(
            List.of(
                new TemporalSequenceObserver.SequenceStep("a", null),
                new TemporalSequenceObserver.SequenceStep("b", null)),
            Duration.ofMinutes(5));
    var snapshot = MAPPER.createObjectNode().put("a", 1).put("b", 2);
    var ctx =
        new ObservationContext(
            snapshot, Set.of("a", "b"), List.of(), "agent-1", "t1", UUID.randomUUID());
    assertTrue(observer.observe(ctx).isEmpty());
  }
}
