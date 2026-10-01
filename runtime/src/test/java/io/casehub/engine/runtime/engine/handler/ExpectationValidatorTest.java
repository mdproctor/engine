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
package io.casehub.engine.internal.engine.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.monitoring.ExpectedEffectResolver;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.plan.goap.Condition;
import io.casehub.engine.plan.monitoring.ExpectedEffects;
import io.casehub.engine.plan.monitoring.MonitoringConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpectationValidatorTest {

  private ExpectedEffectResolver effectResolver;
  private ExpectationValidator validator;

  @BeforeEach
  void setUp() {
    effectResolver = mock(ExpectedEffectResolver.class);
    validator = new ExpectationValidator(effectResolver);
  }

  @Test
  void returns_null_when_monitoring_disabled() {
    CaseDefinition def = buildDefinition(MonitoringConfig.disabled());
    CaseInstance instance = buildInstance(Map.of("resolved", true));
    assertNull(validator.validate(instance, def, "binding", null));
  }

  @Test
  void returns_null_when_no_monitoring_config() {
    CaseDefinition def = buildDefinition(null);
    CaseInstance instance = buildInstance(Map.of());
    assertNull(validator.validate(instance, def, "binding", null));
  }

  @Test
  void returns_null_when_no_expected_effects() {
    CaseDefinition def = buildDefinition(MonitoringConfig.defaults());
    when(effectResolver.resolve(any(), eq("binding")))
        .thenReturn(new ExpectedEffects(Map.of(), ExpectedEffects.EffectSource.GOAP));
    CaseInstance instance = buildInstance(Map.of());
    assertNull(validator.validate(instance, def, "binding", null));
  }

  @Test
  void all_effects_satisfied_returns_zero_ratio() {
    CaseDefinition def = buildDefinition(MonitoringConfig.defaults());
    when(effectResolver.resolve(any(), eq("binding")))
        .thenReturn(
            new ExpectedEffects(
                Map.of("resolved", true, "scored", true), ExpectedEffects.EffectSource.GOAP));
    CaseInstance instance = buildInstance(Map.of("resolved", "yes", "scored", 0.8));

    ExpectationValidationResult result = validator.validate(instance, def, "binding", "compound-1");
    assertNotNull(result);
    assertEquals(0.0, result.divergenceRatio());
    assertTrue(result.violations().isEmpty());
  }

  @Test
  void missing_effect_produces_violation() {
    CaseDefinition def = buildDefinition(MonitoringConfig.defaults());
    when(effectResolver.resolve(any(), eq("binding")))
        .thenReturn(
            new ExpectedEffects(
                Map.of("resolved", true, "scored", true, "verified", true),
                ExpectedEffects.EffectSource.GOAP));
    CaseInstance instance = buildInstance(Map.of("resolved", "yes"));

    ExpectationValidationResult result = validator.validate(instance, def, "binding", null);
    assertNotNull(result);
    assertEquals(2.0 / 3.0, result.divergenceRatio(), 0.001);
    assertEquals(2, result.violations().size());
  }

  @Test
  void false_effect_detected_when_key_present() {
    CaseDefinition def = buildDefinition(MonitoringConfig.defaults());
    when(effectResolver.resolve(any(), eq("binding")))
        .thenReturn(new ExpectedEffects(Map.of("temp", false), ExpectedEffects.EffectSource.GOAP));
    CaseInstance instance = buildInstance(Map.of("temp", "still-here"));

    ExpectationValidationResult result = validator.validate(instance, def, "binding", null);
    assertEquals(1.0, result.divergenceRatio());
    assertEquals(Condition.TRUE, result.violations().get(0).actualCondition());
  }

  @Test
  void unknown_is_violation_for_true_expectation() {
    CaseDefinition def = buildDefinition(MonitoringConfig.defaults());
    when(effectResolver.resolve(any(), eq("binding")))
        .thenReturn(
            new ExpectedEffects(Map.of("missing", true), ExpectedEffects.EffectSource.GOAP));
    CaseInstance instance = buildInstance(Map.of());

    ExpectationValidationResult result = validator.validate(instance, def, "binding", null);
    assertEquals(1.0, result.divergenceRatio());
    assertEquals(Condition.UNKNOWN, result.violations().get(0).actualCondition());
  }

  @Test
  void unknown_is_violation_for_false_expectation() {
    CaseDefinition def = buildDefinition(MonitoringConfig.defaults());
    when(effectResolver.resolve(any(), eq("binding")))
        .thenReturn(new ExpectedEffects(Map.of("temp", false), ExpectedEffects.EffectSource.GOAP));
    CaseInstance instance = buildInstance(Map.of());

    ExpectationValidationResult result = validator.validate(instance, def, "binding", null);
    assertEquals(1.0, result.divergenceRatio());
    assertEquals(Condition.UNKNOWN, result.violations().get(0).actualCondition());
  }

  private CaseDefinition buildDefinition(MonitoringConfig config) {
    CaseDefinition.Builder builder =
        CaseDefinition.builder().namespace("ns").name("test").version("1");
    if (config != null) {
      builder.monitoring(config);
    }
    return builder.build();
  }

  private CaseInstance buildInstance(Map<String, Object> contextData) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseContext(new CaseContextImpl(contextData));
    instance.tenancyId = "test-tenant";
    return instance;
  }
}
