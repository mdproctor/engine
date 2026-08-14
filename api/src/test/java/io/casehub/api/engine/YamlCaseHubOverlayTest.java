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
package io.casehub.api.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.model.CaseDefinition;
import org.junit.jupiter.api.Test;

class YamlCaseHubOverlayTest {

  private static void wireForTest(YamlCaseHub hub) {
    hub.objectMapper = new ObjectMapper(new YAMLFactory());
    hub.expressionEngineRegistry = new JqOnlyExpressionEngineRegistry();
    hub.workerFunctionProviderRegistry = rawWorkerNode -> null;
  }

  @Test
  void conventionOverlayMergesBindingsAndGoals() {
    var hub = new PlainHub("casehub/overlay-base.yaml");
    wireForTest(hub);

    CaseDefinition def = hub.getDefinition();

    assertThat(def.getBindings()).hasSize(2);
    var reviewBinding =
        def.getBindings().stream()
            .filter(b -> "review-trigger".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(reviewBinding.getOn()).isInstanceOf(io.casehub.api.model.ContextChangeTrigger.class);
    var ctxTrigger = (io.casehub.api.model.ContextChangeTrigger) reviewBinding.getOn();
    assertThat(ctxTrigger.getFilter().toString()).contains("priority");

    assertThat(def.getGoals()).hasSize(2);
    assertThat(def.getGoals().stream().map(g -> g.getName()).toList())
        .containsExactly("done", "escalated");
  }

  @Test
  void explicitOverlayAddsCapability() {
    var hub = new PlainHub("casehub/overlay-base.yaml", "casehub/explicit-overlay.yaml");
    wireForTest(hub);

    CaseDefinition def = hub.getDefinition();

    assertThat(def.getCapabilities()).hasSize(3);
    assertThat(def.getCapabilities().stream().map(c -> c.name()).toList()).contains("extra-cap");
  }

  @Test
  void noOverlayReturnsBaseUnchanged() {
    var hub = new PlainHub("casehub/minimal.yaml");
    wireForTest(hub);

    CaseDefinition def = hub.getDefinition();

    assertThat(def.getName()).isEqualTo("Minimal");
    assertThat(def.getCapabilities()).hasSize(1);
    assertThat(def.getBindings()).hasSize(1);
  }

  @Test
  void deriveConventionPathInsertsSuffix() {
    assertThat(YamlCaseHub.deriveConventionPath("templates/pr-review.yaml"))
        .isEqualTo("templates/pr-review-overrides.yaml");
  }

  @Test
  void deriveConventionPathHandlesNoExtension() {
    assertThat(YamlCaseHub.deriveConventionPath("templates/pr-review"))
        .isEqualTo("templates/pr-review-overrides");
  }

  @Test
  void deriveConventionPathHandlesNestedPath() {
    assertThat(YamlCaseHub.deriveConventionPath("a/b/c.yml")).isEqualTo("a/b/c-overrides.yml");
  }

  @Test
  void singleArgConstructorPreservesExistingBehavior() {
    var hub = new PlainHub("casehub/minimal.yaml");
    wireForTest(hub);

    CaseDefinition def = hub.getDefinition();

    assertThat(def.getName()).isEqualTo("Minimal");
    assertThat(def.getCapabilities()).hasSize(1);
  }

  static class PlainHub extends YamlCaseHub {
    PlainHub(String path) {
      super(path);
    }

    PlainHub(String path, String overlayPath) {
      super(path, overlayPath);
    }
  }
}
