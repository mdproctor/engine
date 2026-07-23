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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@QuarkusTest
public class MultiWorkerPipelineBeanTest {

  @Inject MultiWorkerPipelineBean bean;

  @Inject CaseInstanceCache caseInstanceCache;

  // @Test
  public void testMultiWorkerPipeline() {
    AtomicReference<UUID> ref = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    Map<String, Object> initialContext =
        Map.of(
            "documentId", "doc-456",
            "step", "received");

    try {
      ref.set(bean.startCase(initialContext));
    } catch (Exception ex) {
      err.set(ex);
    }

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertNotNull(ref.get());
            });

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(ref.get());
              assertNotNull(instance);
              assertEquals(CaseStatus.COMPLETED, instance.getState());
            });
  }
}
