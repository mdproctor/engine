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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.spi.Resettable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class EngineResetServiceTest {

  @Test
  void reset_calls_all_resettable_beans() {
    List<String> resetLog = new CopyOnWriteArrayList<>();

    Resettable a = () -> resetLog.add("a");
    Resettable b = () -> resetLog.add("b");
    Resettable c = () -> resetLog.add("c");

    EngineResetService service = new EngineResetService(List.of(a, b, c));
    service.reset();

    assertThat(resetLog).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void reset_continues_when_one_bean_throws() {
    List<String> resetLog = new CopyOnWriteArrayList<>();

    Resettable good1 = () -> resetLog.add("good1");
    Resettable bad =
        () -> {
          throw new RuntimeException("boom");
        };
    Resettable good2 = () -> resetLog.add("good2");

    EngineResetService service = new EngineResetService(List.of(good1, bad, good2));
    service.reset();

    assertThat(resetLog)
        .as("both healthy beans must be reset even when one throws")
        .containsExactlyInAnyOrder("good1", "good2");
  }

  @Test
  void reset_with_no_beans_is_noop() {
    EngineResetService service = new EngineResetService(List.of());
    service.reset();
  }
}
