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
package io.casehub.engine.common.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutorRef;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import org.junit.jupiter.api.Test;

class BindingExecutorResolverTest {

  @Test
  void resolvesExecutorFromMatchingWorker() {
    var cap = new Capability("analysis", "", "", null);
    var worker = Worker.builder().name("analyst").capabilityName("analysis").noFunction().build();
    var binding =
        Binding.builder()
            .name("analyse")
            .capability(cap)
            .on(new ContextChangeTrigger(".data != null"))
            .build();
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .workers(worker)
            .bindings(binding)
            .build();

    ExecutorRef result = BindingExecutorResolver.resolve(binding, definition);

    assertThat(result.name()).isEqualTo("analyst");
  }

  @Test
  void fallsBackToCapabilityNameWhenNoWorkerMatches() {
    var cap = new Capability("analysis", "", "", null);
    var binding =
        Binding.builder()
            .name("analyse")
            .capability(cap)
            .on(new ContextChangeTrigger(".data != null"))
            .build();
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .bindings(binding)
            .build();

    ExecutorRef result = BindingExecutorResolver.resolve(binding, definition);

    assertThat(result.name()).isEqualTo("analysis");
  }

  @Test
  void nonCapabilityTargetReturnsUnknown() {
    var binding =
        Binding.builder()
            .name("review")
            .judgment(io.casehub.api.model.JudgmentTarget.builder().prompt("Review").build())
            .on(new ContextChangeTrigger(".needsReview == true"))
            .build();
    var definition = CaseDefinition.builder().namespace("test").name("test").version("1.0").build();

    ExecutorRef result = BindingExecutorResolver.resolve(binding, definition);

    assertThat(result.name()).isEqualTo("unknown");
  }
}
