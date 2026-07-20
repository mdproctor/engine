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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.SignalRejectedException;
import io.casehub.api.model.SignalType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.smallrye.mutiny.Uni;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignalAutoActivationTest {

  @Mock CaseHubReactor reactor;
  @Mock CaseDefinitionRegistry caseDefinitionRegistry;
  @Mock CaseInstanceCache caseInstanceCache;
  @Mock io.casehub.platform.api.routing.StrategyResolver strategyResolver;
  @Mock ReactiveCrossTenantCaseInstanceRepository crossTenantCaseInstanceRepository;

  @InjectMocks CaseHubRuntimeImpl runtime;

  private final SignalType<String> testSignal = SignalType.of("test-signal", String.class);

  @Test
  void signal_to_nonexistent_case_throws_IllegalArgumentException() {
    UUID caseId = UUID.randomUUID();
    when(caseInstanceCache.get(caseId)).thenReturn(null);
    when(crossTenantCaseInstanceRepository.findByUuid(caseId))
        .thenReturn(Uni.createFrom().nullItem());

    assertThatThrownBy(() -> runtime.signal(caseId, testSignal, "payload"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void signal_to_terminal_case_throws_SignalRejectedException() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.COMPLETED);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("ns");
    meta.setName("name");
    meta.setVersion("1.0");
    instance.setCaseMetaModel(meta);
    when(caseInstanceCache.get(caseId)).thenReturn(instance);

    assertThatThrownBy(() -> runtime.signal(caseId, testSignal, "payload"))
        .isInstanceOf(SignalRejectedException.class)
        .hasMessageContaining("terminal state");
  }

  @Test
  void signal_to_dormant_case_auto_activates_and_caches() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.WAITING);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("ns");
    meta.setName("name");
    meta.setVersion("1.0");
    instance.setCaseMetaModel(meta);

    when(caseInstanceCache.get(caseId)).thenReturn(null);
    when(crossTenantCaseInstanceRepository.findByUuid(caseId))
        .thenReturn(Uni.createFrom().item(instance));
    when(caseDefinitionRegistry.getCaseDefinition(meta)).thenReturn(null);
    when(reactor.signalTyped(eq(caseId), eq("test-signal"), eq("payload"), any(), any()))
        .thenReturn(Uni.createFrom().voidItem());

    runtime.signal(caseId, testSignal, "payload");

    verify(caseInstanceCache).put(instance);
    verify(reactor).signalTyped(eq(caseId), eq("test-signal"), eq("payload"), any(), any());
  }
}
