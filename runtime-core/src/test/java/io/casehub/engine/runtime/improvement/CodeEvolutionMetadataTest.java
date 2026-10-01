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
package io.casehub.engine.runtime.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.improvement.ImprovementRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeEvolutionMetadataTest {

  @Test
  void extractPathsFromMetadata() {
    var request =
        new ImprovementRequest(
            "upgrade", "dep", "lodash", 5, Map.of("target-paths", "src/a.java,src/b.java"), null);
    assertThat(CodeEvolutionMetadata.extractPaths(request))
        .containsExactly("src/a.java", "src/b.java");
  }

  @Test
  void extractPathsEmptyWhenNoKey() {
    var request = new ImprovementRequest("upgrade", "dep", "lodash", 5, Map.of(), null);
    assertThat(CodeEvolutionMetadata.extractPaths(request)).isEmpty();
  }

  @Test
  void extractPathsSinglePath() {
    var request =
        new ImprovementRequest(
            "upgrade", "dep", "lodash", 5, Map.of("target-paths", "src/a.java"), null);
    assertThat(CodeEvolutionMetadata.extractPaths(request)).containsExactly("src/a.java");
  }

  @Test
  void extractRepoFromMetadata() {
    var request =
        new ImprovementRequest(
            "upgrade", "dep", "lodash", 5, Map.of("target-repo", "casehubio/engine"), null);
    assertThat(CodeEvolutionMetadata.extractRepo(request)).isEqualTo("casehubio/engine");
  }

  @Test
  void extractRepoReturnsNullWhenMissing() {
    var request = new ImprovementRequest("upgrade", "dep", "lodash", 5, Map.of(), null);
    assertThat(CodeEvolutionMetadata.extractRepo(request)).isNull();
  }

  @Test
  void encodeRoundTrip() {
    var metadata =
        CodeEvolutionMetadata.encode("casehubio/engine", List.of("src/a.java", "src/b.java"));
    var request = new ImprovementRequest("upgrade", "dep", "lodash", 5, metadata, null);
    assertThat(CodeEvolutionMetadata.extractRepo(request)).isEqualTo("casehubio/engine");
    assertThat(CodeEvolutionMetadata.extractPaths(request))
        .containsExactly("src/a.java", "src/b.java");
  }

  @Test
  void encodeWithNullRepoOmitsKey() {
    var metadata = CodeEvolutionMetadata.encode(null, List.of("src/a.java"));
    assertThat(metadata).doesNotContainKey("target-repo");
    assertThat(metadata).containsKey("target-paths");
  }

  @Test
  void encodeWithEmptyPathsOmitsKey() {
    var metadata = CodeEvolutionMetadata.encode("repo", List.of());
    assertThat(metadata).containsKey("target-repo");
    assertThat(metadata).doesNotContainKey("target-paths");
  }
}
