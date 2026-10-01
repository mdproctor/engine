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

import io.casehub.api.engine.InterestSpace;
import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.InterestDeclaration;
import io.casehub.api.spi.observation.InterestLandscape;
import io.casehub.api.spi.observation.InterestRegistration;
import io.casehub.api.spi.observation.ObservationConfig;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class DefaultInterestSpace implements InterestSpace {

  private final ObservationRegistry registry;
  private final UUID caseId;
  private final String agentId;
  private final String bindingName;
  private final ObservationConfig config;
  private final List<InterestRegistration> registrations =
      Collections.synchronizedList(new ArrayList<>());

  public DefaultInterestSpace(
      ObservationRegistry registry,
      UUID caseId,
      String agentId,
      String bindingName,
      ObservationConfig config) {
    this.registry = registry;
    this.caseId = caseId;
    this.agentId = agentId;
    this.bindingName = bindingName;
    this.config = config;
  }

  @Override
  public InterestRegistration register(InterestDeclaration interest) {
    if (interest instanceof InterestDeclaration.JqInterest && !config.allowJqInterests()) {
      throw new IllegalArgumentException(
          "JQ interests are disabled for this case definition (allowJqInterests=false)");
    }

    EnvironmentObserver observer = createObserver(interest);
    String instanceId =
        registry.registerObserver(
            caseId, agentId, bindingName, observer, config.maxObserversPerCase(), interest);
    if (instanceId == null) {
      return null;
    }

    var reg = new InterestRegistration(instanceId, interest, Instant.now());
    registrations.add(reg);
    return reg;
  }

  @Override
  public boolean registerObserver(EnvironmentObserver observer) {
    return registry.registerObserver(
            caseId, agentId, bindingName, observer, config.maxObserversPerCase())
        != null;
  }

  @Override
  public void deregister(String interestId) {
    registrations.removeIf(r -> r.interestId().equals(interestId));
    registry.deregisterByInstanceId(caseId, interestId);
  }

  @Override
  public List<InterestRegistration> mine() {
    return List.copyOf(registrations);
  }

  @Override
  public InterestLandscape landscape() {
    return registry.computeLandscape(caseId);
  }

  private EnvironmentObserver createObserver(InterestDeclaration interest) {
    return switch (interest) {
      case InterestDeclaration.KeyThreshold t ->
          ThresholdObserver.of(t.key(), mapOperator(t.operator()), t.threshold());
      case InterestDeclaration.KeyCorrelation c ->
          CorrelationObserver.of(c.keys(), c.jqCondition());
      case InterestDeclaration.TemporalSequence s ->
          TemporalSequenceObserver.of(
              s.steps().stream()
                  .map(
                      step ->
                          new TemporalSequenceObserver.SequenceStep(
                              step.key(), step.valuePredicate()))
                  .toList(),
              s.window());
      case InterestDeclaration.SignalThreshold st ->
          SignalStrengthObserver.of(st.signalName(), mapOperator(st.operator()), st.threshold());
      case InterestDeclaration.JqInterest j ->
          CorrelationObserver.of(j.watchedKeys(), j.expression());
    };
  }

  private ThresholdObserver.Operator mapOperator(InterestDeclaration.ComparisonOperator op) {
    return switch (op) {
      case GT -> ThresholdObserver.Operator.GT;
      case LT -> ThresholdObserver.Operator.LT;
      case GTE -> ThresholdObserver.Operator.GTE;
      case LTE -> ThresholdObserver.Operator.LTE;
      case EQ -> ThresholdObserver.Operator.EQ;
    };
  }
}
