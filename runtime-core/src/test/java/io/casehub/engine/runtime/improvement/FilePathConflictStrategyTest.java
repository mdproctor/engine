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
import io.casehub.api.spi.improvement.ConflictStrategy.ConflictResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FilePathConflictStrategyTest {

  private final FilePathConflictStrategy strategy = new FilePathConflictStrategy();

  @Test
  void domainIdIsCodeEvolution() {
    assertThat(strategy.domainId()).isEqualTo("code-evolution");
  }

  @Test
  void sameFileConflictDetected() {
    var request = makeRequest(List.of("src/main/Foo.java"), 20);
    var activeId = UUID.randomUUID();
    var active = makeRequest(List.of("src/main/Foo.java"), 10);

    var result = strategy.check(request, Map.of(activeId, active), 10);
    assertThat(result).isInstanceOf(ConflictResult.Conflicting.class);
    var conflicting = (ConflictResult.Conflicting) result;
    assertThat(conflicting.blockingImprovementId()).isEqualTo(activeId);
  }

  @Test
  void sameDirectoryConflictForNonTrivial() {
    var request = makeRequest(List.of("src/main/Foo.java", "src/main/Bar.java"), 20);
    var activeId = UUID.randomUUID();
    var active = makeRequest(List.of("src/main/Baz.java"), 10);

    var result = strategy.check(request, Map.of(activeId, active), 10);
    assertThat(result).isInstanceOf(ConflictResult.Conflicting.class);
  }

  @Test
  void trivialSingleFileAllowsSameDirectory() {
    var request = makeRequest(List.of("src/main/Foo.java"), 5);
    var activeId = UUID.randomUUID();
    var active = makeRequest(List.of("src/main/Bar.java"), 10);

    var result = strategy.check(request, Map.of(activeId, active), 10);
    assertThat(result).isInstanceOf(ConflictResult.Clear.class);
  }

  @Test
  void noPathOverlapReturnsClear() {
    var request = makeRequest(List.of("src/main/Foo.java"), 20);
    var activeId = UUID.randomUUID();
    var active = makeRequest(List.of("src/test/FooTest.java"), 10);

    var result = strategy.check(request, Map.of(activeId, active), 10);
    assertThat(result).isInstanceOf(ConflictResult.Clear.class);
  }

  @Test
  void emptyActiveImprovementsReturnsClear() {
    var request = makeRequest(List.of("src/main/Foo.java"), 20);
    var result = strategy.check(request, Map.of(), 10);
    assertThat(result).isInstanceOf(ConflictResult.Clear.class);
  }

  private ImprovementRequest makeRequest(List<String> paths, int size) {
    return new ImprovementRequest(
        "upgrade",
        "dependency-update",
        "target",
        size,
        CodeEvolutionMetadata.encode(null, paths),
        "code-evolution");
  }
}
