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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.routing.CandidateMatchingContext;
import io.casehub.api.spi.routing.MatchedWorker;
import io.casehub.eidos.api.MatchDegree;
import io.casehub.worker.api.Worker;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactMatchStrategyTest {

  private final ExactMatchStrategy strategy = new ExactMatchStrategy();

  @Test
  void idIsExact() {
    assertThat(strategy.id()).isEqualTo("exact");
  }

  @Test
  void matchesWorkerWithCapability() {
    Worker w1 = Worker.builder().name("w1").capabilityName("cap-a").noFunction().build();
    Worker w2 = Worker.builder().name("w2").capabilityName("cap-b").noFunction().build();
    var ctx = new CandidateMatchingContext("cap-a", List.of(w1, w2), null);
    List<MatchedWorker> result = strategy.match(ctx);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).worker().name()).isEqualTo("w1");
    assertThat(result.get(0).matchDegree()).isInstanceOf(MatchDegree.Exact.class);
  }

  @Test
  void noMatchReturnsEmpty() {
    Worker w1 = Worker.builder().name("w1").capabilityName("cap-b").noFunction().build();
    var ctx = new CandidateMatchingContext("cap-a", List.of(w1), null);
    List<MatchedWorker> result = strategy.match(ctx);
    assertThat(result).isEmpty();
  }

  @Test
  void multipleCapabilitiesOnWorker() {
    Worker w1 =
        Worker.builder()
            .name("w1")
            .capabilityName("cap-a")
            .capabilityName("cap-b")
            .noFunction()
            .build();
    var ctx = new CandidateMatchingContext("cap-b", List.of(w1), null);
    List<MatchedWorker> result = strategy.match(ctx);
    assertThat(result).hasSize(1);
  }
}
