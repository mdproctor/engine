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

import java.time.Duration;
import java.util.List;
import java.util.Set;

public sealed interface InterestDeclaration
    permits InterestDeclaration.KeyThreshold,
        InterestDeclaration.KeyCorrelation,
        InterestDeclaration.TemporalSequence,
        InterestDeclaration.SignalThreshold,
        InterestDeclaration.JqInterest {

  enum ComparisonOperator {
    GT,
    LT,
    GTE,
    LTE,
    EQ
  }

  record SequenceStep(String key, String valuePredicate) {}

  record KeyThreshold(String key, ComparisonOperator operator, double threshold)
      implements InterestDeclaration {}

  record KeyCorrelation(Set<String> keys, String jqCondition) implements InterestDeclaration {}

  record TemporalSequence(List<SequenceStep> steps, Duration window)
      implements InterestDeclaration {}

  record SignalThreshold(String signalName, ComparisonOperator operator, double threshold)
      implements InterestDeclaration {}

  record JqInterest(String expression, Set<String> watchedKeys) implements InterestDeclaration {}
}
