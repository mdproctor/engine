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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.casehub.api.model.signal.PerceivedSignal;
import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SignalStrengthObserver implements EnvironmentObserver {

  private final String signalName;
  private final ThresholdObserver.Operator operator;
  private final double threshold;

  private SignalStrengthObserver(
      String signalName, ThresholdObserver.Operator operator, double threshold) {
    this.signalName = signalName;
    this.operator = operator;
    this.threshold = threshold;
  }

  public static SignalStrengthObserver of(
      String signalName, ThresholdObserver.Operator operator, double threshold) {
    return new SignalStrengthObserver(signalName, operator, threshold);
  }

  @Override
  public String observerType() {
    return "signal-strength";
  }

  @Override
  public Set<String> watchedKeys() {
    return Set.of();
  }

  @Override
  public List<Observation> observe(ObservationContext ctx) {
    PerceivedSignal signal = ctx.signals().get(signalName);
    if (signal == null) return List.of();

    double value = signal.effectiveStrength();
    boolean crossed =
        switch (operator) {
          case GT -> value > threshold;
          case LT -> value < threshold;
          case GTE -> value >= threshold;
          case LTE -> value <= threshold;
          case EQ -> Double.compare(value, threshold) == 0;
        };
    if (!crossed) return List.of();

    Map<String, JsonNode> details = new LinkedHashMap<>();
    details.put("signalName", TextNode.valueOf(signalName));
    details.put("effectiveStrength", DoubleNode.valueOf(value));
    details.put("threshold", DoubleNode.valueOf(threshold));
    details.put("operator", TextNode.valueOf(operator.name()));
    details.put("reinforcementCount", IntNode.valueOf(signal.reinforcementCount()));
    return List.of(new Observation("signal-strength-crossing", 1.0, details, Instant.now()));
  }
}
