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
package io.casehub.engine.internal.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.RiskDecision.Autonomous;
import io.casehub.api.spi.RiskDecision.GateRequired;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.callback.CallbackInvoker;
import io.casehub.worker.api.PlannedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallbackActionRiskClassifierTest {

  private CallbackRegistry callbackRegistry;
  private CallbackInvoker callbackInvoker;
  private CurrentPrincipal currentPrincipal;
  private CallbackActionRiskClassifier classifier;

  private static final String TENANT_ID = "tenant-1";
  private static final String SPI_NAME = "action-risk-classifier";

  @BeforeEach
  void setUp() {
    callbackRegistry = mock(CallbackRegistry.class);
    callbackInvoker = mock(CallbackInvoker.class);
    currentPrincipal = mock(CurrentPrincipal.class);
    when(currentPrincipal.tenancyId()).thenReturn(TENANT_ID);

    classifier =
        new CallbackActionRiskClassifier(callbackRegistry, callbackInvoker, currentPrincipal);
  }

  @Test
  void noRegistrations_returnsAutonomous() {
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of());

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(Autonomous.class);
    verify(callbackInvoker, never()).invoke(any(), any(), any(), any());
  }

  @Test
  void singleRegistration_invokesRemoteAndReturnsResult() {
    final CallbackRegistration reg = registration("http://app1/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg));

    final GateRequired remoteDecision =
        new GateRequired("high risk", true, null, Duration.ofMinutes(30), null, null, null);
    when(callbackInvoker.invoke(eq(reg), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(remoteDecision);

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(GateRequired.class);
    assertThat(((GateRequired) result).reason()).isEqualTo("high risk");
  }

  @Test
  void multipleRegistrations_returnsMostRestrictive() {
    final CallbackRegistration reg1 = registration("http://app1/callback");
    final CallbackRegistration reg2 = registration("http://app2/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg1, reg2));

    final GateRequired wideGate =
        new GateRequired(
            "moderate risk",
            true,
            StaticSetStrategy.of("manager", "senior", "lead"),
            Duration.ofHours(1),
            null,
            null,
            null);
    final GateRequired narrowGate =
        new GateRequired(
            "high risk",
            true,
            StaticSetStrategy.of("manager"),
            Duration.ofMinutes(30),
            null,
            null,
            null);

    when(callbackInvoker.invoke(eq(reg1), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(wideGate);
    when(callbackInvoker.invoke(eq(reg2), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(narrowGate);

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(GateRequired.class);
    final GateRequired gate = (GateRequired) result;
    assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).hasSize(1);
  }

  @Test
  void remoteReturnsAutonomous_overallAutonomous() {
    final CallbackRegistration reg = registration("http://app1/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg));
    when(callbackInvoker.invoke(eq(reg), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(new Autonomous());

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  @Test
  void allRemoteThrow_returnsFailSafeGateRequired() {
    final CallbackRegistration reg = registration("http://app1/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg));
    when(callbackInvoker.invoke(eq(reg), eq("classify"), any(), eq(RiskDecision.class)))
        .thenThrow(new RuntimeException("connection refused"));

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(GateRequired.class);
    final GateRequired gate = (GateRequired) result;
    assertThat(gate.reversible()).isTrue();
  }

  @Test
  void oneRemoteFails_otherReturnsGate_usesSuccessfulResult() {
    final CallbackRegistration reg1 = registration("http://app1/callback");
    final CallbackRegistration reg2 = registration("http://app2/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg1, reg2));

    when(callbackInvoker.invoke(eq(reg1), eq("classify"), any(), eq(RiskDecision.class)))
        .thenThrow(new RuntimeException("timeout"));
    final GateRequired gate =
        new GateRequired("policy violation", true, null, null, null, null, null);
    when(callbackInvoker.invoke(eq(reg2), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(gate);

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(GateRequired.class);
    assertThat(((GateRequired) result).reason()).isEqualTo("policy violation");
  }

  @Test
  void mixedRemoteResults_gateWinsOverAutonomous() {
    final CallbackRegistration reg1 = registration("http://app1/callback");
    final CallbackRegistration reg2 = registration("http://app2/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg1, reg2));

    when(callbackInvoker.invoke(eq(reg1), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(new Autonomous());
    final GateRequired gate =
        new GateRequired("policy violation", true, null, null, null, null, null);
    when(callbackInvoker.invoke(eq(reg2), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(gate);

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(GateRequired.class);
    assertThat(((GateRequired) result).reason()).isEqualTo("policy violation");
  }

  @Test
  void remoteReturnsNull_treatedAsAutonomous() {
    final CallbackRegistration reg = registration("http://app1/callback");
    when(callbackRegistry.findBySpi(SPI_NAME, TENANT_ID)).thenReturn(List.of(reg));
    when(callbackInvoker.invoke(eq(reg), eq("classify"), any(), eq(RiskDecision.class)))
        .thenReturn(null);

    final RiskDecision result = classifier.classify(testAction(), testContext());

    assertThat(result).isInstanceOf(Autonomous.class);
  }

  private static PlannedAction testAction() {
    return mock(PlannedAction.class);
  }

  private static ClassificationContext testContext() {
    return new ClassificationContext(
        "worker-1", UUID.randomUUID(), TENANT_ID, "investigation", "analyse", "binding-0");
  }

  private static CallbackRegistration registration(final String url) {
    return new CallbackRegistration(
        UUID.randomUUID().toString(),
        SPI_NAME,
        url,
        "cred-ref",
        TENANT_ID,
        5000,
        Map.of(),
        Instant.now(),
        Instant.now().plusSeconds(300),
        Instant.now());
  }
}
