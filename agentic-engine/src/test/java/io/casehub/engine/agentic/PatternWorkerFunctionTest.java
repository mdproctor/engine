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
package io.casehub.engine.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.blocks.agentic.model.PatternType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternWorkerFunctionTest {

  @Test
  void inputAndOutputTypesAreMap() {
    var fn = new PatternWorkerFunction(null, PatternType.DEBATE, false);
    assertThat(fn.inputType()).isEqualTo(Map.class);
    assertThat(fn.outputType()).isEqualTo(Map.class);
  }

  @Test
  void recordFieldsAccessible() {
    var fn = new PatternWorkerFunction(null, PatternType.HTN, true);
    assertThat(fn.patternType()).isEqualTo(PatternType.HTN);
    assertThat(fn.checkpointingEnabled()).isTrue();
  }

  @Test
  void nullModelAccepted() {
    var fn = new PatternWorkerFunction(null, PatternType.SEQUENCE, false);
    assertThat(fn.model()).isNull();
  }
}
