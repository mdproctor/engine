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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.ImprovementRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConflictDetectorTest {

  private ConflictDetector detector;
  private UUID activeId;

  @BeforeEach
  void setUp() {
    detector = new ConflictDetector();
    activeId = UUID.randomUUID();
  }

  @Test
  void noOverlapIsClear() {
    var request = request("module-a/src/Foo.java", 50);
    var active = Map.of(activeId, request("module-b/src/Bar.java", 50));
    assertThat(detector.check(request, active, 10))
        .isInstanceOf(ConflictDetector.ConflictCheck.Clear.class);
  }

  @Test
  void fileOverlapIsConflicting() {
    var request = request("module-a/src/Foo.java", 50);
    var active = Map.of(activeId, request("module-a/src/Foo.java", 50));
    assertThat(detector.check(request, active, 10))
        .isInstanceOf(ConflictDetector.ConflictCheck.Conflicting.class);
  }

  @Test
  void directoryOverlapIsConflicting() {
    var request = request("module-a/src/Foo.java", 50);
    var active = Map.of(activeId, request("module-a/src/Bar.java", 50));
    assertThat(detector.check(request, active, 10))
        .isInstanceOf(ConflictDetector.ConflictCheck.Conflicting.class);
  }

  @Test
  void trivialExemptFromDirectoryOverlap() {
    var request = request("module-a/src/Foo.java", 5);
    var active = Map.of(activeId, request("module-a/src/Bar.java", 50));
    assertThat(detector.check(request, active, 10))
        .isInstanceOf(ConflictDetector.ConflictCheck.Clear.class);
  }

  @Test
  void trivialNotExemptFromFileOverlap() {
    var request = request("module-a/src/Foo.java", 5);
    var active = Map.of(activeId, request("module-a/src/Foo.java", 50));
    assertThat(detector.check(request, active, 10))
        .isInstanceOf(ConflictDetector.ConflictCheck.Conflicting.class);
  }

  @Test
  void emptyActiveIsClear() {
    var request = request("module-a/src/Foo.java", 50);
    assertThat(detector.check(request, Map.of(), 10))
        .isInstanceOf(ConflictDetector.ConflictCheck.Clear.class);
  }

  private ImprovementRequest request(String path, int size) {
    return new ImprovementRequest(
        "operational", "lint-fix", "target", "casehubio/engine", List.of(path), size, Map.of());
  }
}
