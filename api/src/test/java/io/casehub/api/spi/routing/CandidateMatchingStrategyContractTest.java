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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.routing.NamedStrategy;
import io.casehub.worker.api.Worker;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateMatchingStrategyContractTest {

  @Test
  void strategyExtendsNamedStrategy() {
    CandidateMatchingStrategy strategy =
        new CandidateMatchingStrategy() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public List<MatchedWorker> match(CandidateMatchingContext context) {
            return List.of();
          }
        };
    assertThat(strategy).isInstanceOf(NamedStrategy.class);
  }

  @Test
  void contextCarriesCapabilityAndWorkers() {
    Worker worker = Worker.builder().name("w1").capabilityName("cap-a").noFunction().build();
    var ctx = new CandidateMatchingContext("cap-a", List.of(worker), null);
    assertThat(ctx.capabilityName()).isEqualTo("cap-a");
    assertThat(ctx.workers()).hasSize(1);
  }
}
