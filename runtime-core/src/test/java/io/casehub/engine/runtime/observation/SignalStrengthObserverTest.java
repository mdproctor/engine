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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.casehub.api.model.signal.PerceivedSignal;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignalStrengthObserverTest {

  @Test
  void observerType_returnsSignalStrength() {
    SignalStrengthObserver observer =
        SignalStrengthObserver.of("trail", ThresholdObserver.Operator.GT, 0.5);
    assertThat(observer.observerType()).isEqualTo("signal-strength");
  }

  @Test
  void watchedKeys_returnsEmpty() {
    SignalStrengthObserver observer =
        SignalStrengthObserver.of("trail", ThresholdObserver.Operator.GT, 0.5);
    assertThat(observer.watchedKeys()).isEmpty();
  }

  @Test
  void observe_signalAboveThreshold_returnsObservation() {
    SignalStrengthObserver observer =
        SignalStrengthObserver.of("trail", ThresholdObserver.Operator.GT, 0.5);
    PerceivedSignal signal =
        new PerceivedSignal("trail", 0.8, 3, "agent-a", Duration.ofSeconds(30));
    ObservationContext ctx = contextWithSignals(Map.of("trail", signal));

    List<Observation> results = observer.observe(ctx);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).patternId()).isEqualTo("signal-strength-crossing");
    assertThat(results.get(0).confidence()).isEqualTo(1.0);
  }

  @Test
  void observe_signalBelowThreshold_returnsEmpty() {
    SignalStrengthObserver observer =
        SignalStrengthObserver.of("trail", ThresholdObserver.Operator.GT, 0.5);
    PerceivedSignal signal =
        new PerceivedSignal("trail", 0.3, 1, "agent-a", Duration.ofSeconds(30));
    ObservationContext ctx = contextWithSignals(Map.of("trail", signal));

    List<Observation> results = observer.observe(ctx);
    assertThat(results).isEmpty();
  }

  @Test
  void observe_signalAbsent_returnsEmpty() {
    SignalStrengthObserver observer =
        SignalStrengthObserver.of("trail", ThresholdObserver.Operator.GT, 0.5);
    ObservationContext ctx = contextWithSignals(Map.of());

    List<Observation> results = observer.observe(ctx);
    assertThat(results).isEmpty();
  }

  @Test
  void observe_ltOperator_detectsBelowThreshold() {
    SignalStrengthObserver observer =
        SignalStrengthObserver.of("weak-signal", ThresholdObserver.Operator.LT, 0.2);
    PerceivedSignal signal =
        new PerceivedSignal("weak-signal", 0.1, 1, "agent-a", Duration.ofMinutes(1));
    ObservationContext ctx = contextWithSignals(Map.of("weak-signal", signal));

    List<Observation> results = observer.observe(ctx);
    assertThat(results).hasSize(1);
  }

  private ObservationContext contextWithSignals(Map<String, PerceivedSignal> signals) {
    JsonNode emptyNode = JsonNodeFactory.instance.objectNode();
    return new ObservationContext(
        emptyNode, Set.of(), List.of(), "test-agent", "tenant-1", UUID.randomUUID(), signals);
  }
}
