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
package io.casehub.engine;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.SignalRejectedException;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TypedSignalIntegrationTest {

  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventLogRepository eventLogRepository;
  @Inject TypedSignalCaseBean typedSignalCaseBean;

  public record PaymentEvent(String txnId, double amount) {}

  static final SignalType<PaymentEvent> PAYMENT_SIGNAL =
      SignalType.of("payment-received", PaymentEvent.class);

  @ApplicationScoped
  public static class TypedSignalCaseBean extends CaseHub {

    static final AtomicReference<Object> receivedSignalData = new AtomicReference<>();

    @Override
    public CaseDefinition getDefinition() {
      Capability process =
          Capability.builder()
              .name("process-payment")
              .inputSchema(".signals")
              .outputSchema(".")
              .build();
      return CaseDefinition.builder()
          .namespace("test")
          .name("typed-signal-case")
          .version("1.0.0")
          .signal(PAYMENT_SIGNAL)
          .capabilities(process)
          .workers(
              Worker.builder()
                  .name("payment-worker")
                  .capabilityName("process-payment")
                  .function(
                      input -> {
                        receivedSignalData.set(input);
                        return WorkerResult.of(Map.of("processed", true));
                      })
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-payment")
                  .capability(process)
                  .on(new ContextChangeTrigger(".signals"))
                  .build())
          .goals(
              Goal.builder()
                  .name("done")
                  .kind(io.casehub.api.model.GoalKind.SUCCESS)
                  .condition(".processed == true")
                  .build())
          .completion(GoalExpression.goal("done"))
          .build();
    }
  }

  @Test
  void typedSignal_writesToSignalsNamespace() throws Exception {
    UUID caseId = typedSignalCaseBean.startCase(Map.of());

    runtime.signal(caseId, PAYMENT_SIGNAL, new PaymentEvent("T1", 99.99));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              var ctx = instance.getCaseContext();
              assertThat(ctx.get("signals")).isNotNull();
            });
  }

  @Test
  void typedSignal_rejectedWhenNotDeclared() throws Exception {
    UUID caseId = typedSignalCaseBean.startCase(Map.of());

    SignalType<String> undeclared = SignalType.of("unknown-signal", String.class);
    assertThatThrownBy(() -> runtime.signal(caseId, undeclared, "test"))
        .isInstanceOf(SignalRejectedException.class);
  }

  @Test
  void typedSignal_rejectedWhenPayloadTypeMismatch() throws Exception {
    UUID caseId = typedSignalCaseBean.startCase(Map.of());

    SignalType<String> wrongType = SignalType.of("payment-received", String.class);
    assertThatThrownBy(() -> runtime.signal(caseId, wrongType, "wrong"))
        .isInstanceOf(SignalRejectedException.class);
  }

  @Test
  void typedSignal_nullPayload_rejected() {
    assertThatThrownBy(() -> runtime.signal(UUID.randomUUID(), PAYMENT_SIGNAL, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void typedSignal_eventLogCarriesMetadata() throws Exception {
    UUID caseId = typedSignalCaseBean.startCase(Map.of());

    runtime.signal(caseId, PAYMENT_SIGNAL, new PaymentEvent("T2", 50.0));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              var logs =
                  eventLogRepository.findByCaseAndTypes(
                      caseId,
                      java.util.List.of(CaseHubEventType.SIGNAL_RECEIVED),
                      TenancyConstants.DEFAULT_TENANT_ID);
              var signalLogs =
                  logs.stream()
                      .filter(
                          l ->
                              l.getEventType() == CaseHubEventType.SIGNAL_RECEIVED
                                  && l.getMetadata() != null
                                  && l.getMetadata().has("signalTypeName"))
                      .toList();
              assertThat(signalLogs).isNotEmpty();
              var meta = signalLogs.get(0).getMetadata();
              assertThat(meta.get("signalTypeName").asText()).isEqualTo("payment-received");
              assertThat(meta.get("payloadType").asText()).isEqualTo(PaymentEvent.class.getName());
            });
  }
}
