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
package io.casehub.engine.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.ledger.api.spi.TrustScoreSource;
import jakarta.enterprise.inject.Instance;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class TrustScoreAgentTrustProviderTest {

  @Test
  void delegatesToTrustScoreSource() {
    TrustScoreSource source = mock(TrustScoreSource.class);
    when(source.globalScore("agent-1")).thenReturn(OptionalDouble.of(0.85));

    @SuppressWarnings("unchecked")
    Instance<TrustScoreSource> instance = mock(Instance.class);
    when(instance.isUnsatisfied()).thenReturn(false);
    when(instance.get()).thenReturn(source);

    var provider = new TrustScoreAgentTrustProvider(instance);

    assertThat(provider.currentTrustScore("agent-1")).hasValue(0.85);
  }

  @Test
  void returnsEmptyWhenSourceUnsatisfied() {
    @SuppressWarnings("unchecked")
    Instance<TrustScoreSource> instance = mock(Instance.class);
    when(instance.isUnsatisfied()).thenReturn(true);

    var provider = new TrustScoreAgentTrustProvider(instance);

    assertThat(provider.currentTrustScore("agent-1")).isEmpty();
  }

  @Test
  void returnsEmptyForNullAgentId() {
    @SuppressWarnings("unchecked")
    Instance<TrustScoreSource> instance = mock(Instance.class);

    var provider = new TrustScoreAgentTrustProvider(instance);

    assertThat(provider.currentTrustScore(null)).isEmpty();
  }

  @Test
  void returnsEmptyWhenSourceReturnsEmpty() {
    TrustScoreSource source = mock(TrustScoreSource.class);
    when(source.globalScore("unknown-agent")).thenReturn(OptionalDouble.empty());

    @SuppressWarnings("unchecked")
    Instance<TrustScoreSource> instance = mock(Instance.class);
    when(instance.isUnsatisfied()).thenReturn(false);
    when(instance.get()).thenReturn(source);

    var provider = new TrustScoreAgentTrustProvider(instance);

    assertThat(provider.currentTrustScore("unknown-agent")).isEmpty();
  }
}
