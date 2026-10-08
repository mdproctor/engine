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
package io.casehub.engine.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DefaultCaseDefinitionRegistryConcurrencyTest {

  @Inject DefaultCaseDefinitionRegistry registry;

  @Test
  void concurrentLookupsDuringReRegistration_neverSeePartialState() throws Exception {
    // Register definitions first so we have known keys to look up
    CaseDefinition def1 =
        CaseDefinition.builder().namespace("conc-ns").name("conc-case-1").version("1.0").build();
    CaseDefinition def2 =
        CaseDefinition.builder().namespace("conc-ns").name("conc-case-2").version("1.0").build();
    CaseDefinition def3 =
        CaseDefinition.builder().namespace("conc-ns").name("conc-case-3").version("1.0").build();

    CaseMetaModel meta1 = registry.registerCaseDefinition(def1);
    CaseMetaModel meta2 = registry.registerCaseDefinition(def2);
    CaseMetaModel meta3 = registry.registerCaseDefinition(def3);

    List<String> failures = new CopyOnWriteArrayList<>();
    int readerCount = 10;
    int iterations = 200;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(readerCount + 1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Writer: re-registers known definitions repeatedly
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < iterations; i++) {
                registry.registerKnownDefinitions();
              }
            } catch (Exception e) {
              failures.add("Writer: " + e.getMessage());
            } finally {
              doneLatch.countDown();
            }
          });

      // Readers: look up definitions concurrently
      CaseMetaModel[] metas = {meta1, meta2, meta3};
      for (int r = 0; r < readerCount; r++) {
        executor.submit(
            () -> {
              try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                  for (CaseMetaModel meta : metas) {
                    CaseDefinition found = registry.getCaseDefinition(meta);
                    if (found == null) {
                      failures.add(
                          "Lookup miss for "
                              + meta.getName()
                              + " — partial registry state observed");
                    }
                  }
                }
              } catch (Exception e) {
                failures.add("Reader: " + e.getMessage());
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(failures).as("No reader should observe partial registry state").isEmpty();
  }
}
