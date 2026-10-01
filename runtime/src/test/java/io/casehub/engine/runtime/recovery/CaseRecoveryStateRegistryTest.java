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
package io.casehub.engine.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.spi.recovery.CaseRecoveryState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseRecoveryStateRegistryTest {

  private final CaseRecoveryStateRegistry registry = new CaseRecoveryStateRegistry();

  @Test
  void getOrCreateReturnsSameInstance() {
    UUID caseId = UUID.randomUUID();
    CaseRecoveryState s1 = registry.getOrCreate(caseId);
    CaseRecoveryState s2 = registry.getOrCreate(caseId);
    assertThat(s1).isSameAs(s2);
  }

  @Test
  void evictRemovesState() {
    UUID caseId = UUID.randomUUID();
    CaseRecoveryState s1 = registry.getOrCreate(caseId);
    registry.evict(caseId);
    CaseRecoveryState s2 = registry.getOrCreate(caseId);
    assertThat(s2).isNotSameAs(s1);
  }

  @Test
  void distinctCasesGetDistinctStates() {
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    assertThat(registry.getOrCreate(c1)).isNotSameAs(registry.getOrCreate(c2));
  }
}
