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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseKey;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression test for engine#480 — detects duplicate CaseDefinition keys across all CaseHub beans
 * on the test classpath.
 *
 * <p>The registry throws on duplicate keys (fail-fast), so a collision would prevent the
 * application from starting at all. This test provides an explicit assertion with a diagnostic
 * message naming both colliding beans, making collisions easy to fix when they occur.
 */
@QuarkusTest
class CaseKeyUniquenessTest {

  @Inject Instance<CaseHub> caseHubs;

  @Test
  void allCaseHubBeans_haveUniqueDefinitionKeys() {
    Map<CaseKey, String> seen = new LinkedHashMap<>();

    for (CaseHub hub : caseHubs) {
      CaseDefinition def = hub.getDefinition();
      CaseKey key = CaseKey.of(def);
      String beanDesc = describeBean(hub);

      String existing = seen.get(key);
      assertThat(existing)
          .as(
              "CaseKey collision: %s/%s/%s registered by both [%s] and [%s]",
              key.namespace(), key.name(), key.version(), existing, beanDesc)
          .isNull();

      seen.put(key, beanDesc);
    }
  }

  private static String describeBean(CaseHub hub) {
    String className = hub.getClass().getName();
    if (hub instanceof YamlCaseHub) {
      return className + " (YAML)";
    }
    return className;
  }
}
