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
package io.casehub.api.spi.observation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InterestDeclarationTest {

  @Test
  void keyThresholdConstruction() {
    var interest =
        new InterestDeclaration.KeyThreshold(
            "riskScore", InterestDeclaration.ComparisonOperator.GTE, 0.7);
    assertEquals("riskScore", interest.key());
    assertEquals(InterestDeclaration.ComparisonOperator.GTE, interest.operator());
    assertEquals(0.7, interest.threshold());
  }

  @Test
  void keyCorrelationConstruction() {
    var interest =
        new InterestDeclaration.KeyCorrelation(
            Set.of("amount", "country"), ".amount > 10000 and .country == \"US\"");
    assertEquals(Set.of("amount", "country"), interest.keys());
    assertEquals(".amount > 10000 and .country == \"US\"", interest.jqCondition());
  }

  @Test
  void temporalSequenceConstruction() {
    var steps =
        List.of(
            new InterestDeclaration.SequenceStep("login", null),
            new InterestDeclaration.SequenceStep("transfer", null));
    var interest = new InterestDeclaration.TemporalSequence(steps, Duration.ofMinutes(5));
    assertEquals(2, interest.steps().size());
    assertEquals(Duration.ofMinutes(5), interest.window());
  }

  @Test
  void signalThresholdConstruction() {
    var interest =
        new InterestDeclaration.SignalThreshold(
            "danger", InterestDeclaration.ComparisonOperator.GT, 0.5);
    assertEquals("danger", interest.signalName());
    assertEquals(InterestDeclaration.ComparisonOperator.GT, interest.operator());
  }

  @Test
  void jqInterestConstruction() {
    var interest =
        new InterestDeclaration.JqInterest(
            ".riskScore > .threshold", Set.of("riskScore", "threshold"));
    assertEquals(".riskScore > .threshold", interest.expression());
    assertEquals(Set.of("riskScore", "threshold"), interest.watchedKeys());
  }

  @Test
  void sealedHierarchyPatternMatching() {
    InterestDeclaration interest =
        new InterestDeclaration.KeyThreshold("x", InterestDeclaration.ComparisonOperator.GT, 1.0);
    String type =
        switch (interest) {
          case InterestDeclaration.KeyThreshold t -> "threshold";
          case InterestDeclaration.KeyCorrelation c -> "correlation";
          case InterestDeclaration.TemporalSequence s -> "sequence";
          case InterestDeclaration.SignalThreshold s -> "signal";
          case InterestDeclaration.JqInterest j -> "jq";
        };
    assertEquals("threshold", type);
  }
}
