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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.worker.api.Capability;
import org.junit.jupiter.api.Test;

class CaseDefinitionBindingLookupTest {

  @Test
  void findBindingsByCapability_singleMatch() {
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

    var result = definition.findBindingsByCapability("analysis");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("analyse");
  }

  @Test
  void findBindingsByCapability_multipleBindings_declarationOrder() {
    var cap = new Capability("analysis", "", "", null);
    var b1 =
        Binding.builder()
            .name("quick-analyse")
            .capability(cap)
            .on(new ContextChangeTrigger(".data != null"))
            .build();
    var b2 =
        Binding.builder()
            .name("deep-analyse")
            .capability(cap)
            .on(new ContextChangeTrigger(".data != null"))
            .build();
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .bindings(b1, b2)
            .build();

    var result = definition.findBindingsByCapability("analysis");

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getName()).isEqualTo("quick-analyse");
    assertThat(result.get(1).getName()).isEqualTo("deep-analyse");
  }

  @Test
  void findBindingsByCapability_noMatch() {
    var cap = new Capability("research", "", "", null);
    var binding =
        Binding.builder()
            .name("research-binding")
            .capability(cap)
            .on(new ContextChangeTrigger(".topic != null"))
            .build();
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .bindings(binding)
            .build();

    var result = definition.findBindingsByCapability("nonexistent");

    assertThat(result).isEmpty();
  }

  @Test
  void findBindingsByCapability_excludesNonCapabilityTargets() {
    var cap = new Capability("analysis", "", "", null);
    var capBinding =
        Binding.builder()
            .name("analyse")
            .capability(cap)
            .on(new ContextChangeTrigger(".data != null"))
            .build();
    var htBinding =
        Binding.builder()
            .name("review")
            .humanTask(HumanTaskTarget.inline().title("Review task").build())
            .on(new ContextChangeTrigger(".needsReview == true"))
            .build();
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .capabilities(cap)
            .bindings(capBinding, htBinding)
            .build();

    var result = definition.findBindingsByCapability("analysis");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("analyse");
  }
}
