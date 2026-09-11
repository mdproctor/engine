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
package io.casehub.actorstate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Regression test for engine#481 — validates that every class name in {@code
 * quarkus.arc.exclude-types} actually exists on the classpath. Quarkus ARC silently ignores
 * excludes for non-existent classes, so stale entries (from upstream renames) give no feedback
 * until the CDI validation fails on the beans that were supposed to be excluded.
 */
class ExcludeTypesValidationTest {

  @Test
  void allExcludedTypesExistOnClasspath() throws IOException {
    Properties props = new Properties();
    try (InputStream is =
        getClass().getClassLoader().getResourceAsStream("application.properties")) {
      assertThat(is).as("application.properties must be on the test classpath").isNotNull();
      props.load(is);
    }

    String excludeTypes = props.getProperty("quarkus.arc.exclude-types");
    assertThat(excludeTypes).as("quarkus.arc.exclude-types must be defined").isNotNull();

    List<String> missing = new ArrayList<>();
    for (String entry : excludeTypes.split(",")) {
      String className = entry.trim();
      if (className.isEmpty() || className.startsWith("/")) {
        continue; // skip empty entries and regex patterns
      }
      try {
        Class.forName(className, false, getClass().getClassLoader());
      } catch (ClassNotFoundException e) {
        missing.add(className);
      }
    }

    assertThat(missing)
        .as(
            "Stale quarkus.arc.exclude-types entries — these classes no longer exist on the classpath. "
                + "Upstream renames? Update the exclude list to match current class names.")
        .isEmpty();
  }
}
