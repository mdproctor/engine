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
package io.casehub.engine.internal.improvement.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.ImprovementRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalWorkerTest {

  @Test
  void dependencyUpdateWorkerDeclaresCategory() {
    assertThat(new DependencyUpdateWorker().category()).isEqualTo("dependency-update");
  }

  @Test
  void lintFixWorkerDeclaresCategory() {
    assertThat(new LintFixWorker().category()).isEqualTo("lint-fix");
  }

  @Test
  void coverageGapWorkerDeclaresCategory() {
    assertThat(new CoverageGapWorker().category()).isEqualTo("coverage-gap");
  }

  @Test
  void ciTriageWorkerDeclaresCategory() {
    assertThat(new CITriageWorker().category()).isEqualTo("ci-triage");
  }

  @Test
  void recipeWorkerDeclaresCategory() {
    assertThat(new RecipeWorker().category()).isEqualTo("recipe");
  }

  @Test
  void dependencyUpdateIntrospectProducesResult() {
    var worker = new DependencyUpdateWorker();
    var request =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate-core",
            "casehubio/engine",
            List.of("pom.xml"),
            20,
            Map.of());
    var result = worker.introspect(request);
    assertThat(result).isNotNull();
    assertThat(result.category()).isEqualTo("dependency-update");
    assertThat(result.affectedPaths()).containsExactly("pom.xml");
    assertThat(result.estimatedSize()).isEqualTo(20);
  }

  @Test
  void dependencyUpdateIntrospectDefaultsPathWhenEmpty() {
    var worker = new DependencyUpdateWorker();
    var request =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate-core",
            "casehubio/engine",
            List.of(),
            0,
            Map.of());
    var result = worker.introspect(request);
    assertThat(result.affectedPaths()).containsExactly("pom.xml");
    assertThat(result.estimatedSize()).isEqualTo(30);
  }
}
