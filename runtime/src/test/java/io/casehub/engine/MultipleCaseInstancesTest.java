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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class MultipleCaseInstancesTest {

  @Inject SimpleCaseHubBean bean;

  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  public void testTwoSequentialCasesFromSameDefinition() {
    UUID caseId1 = startCaseAndAwait("doc-001");
    UUID caseId2 = startCaseAndAwait("doc-002");

    assertNotEquals(caseId1, caseId2, "Each case should have a unique id");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance1 = caseInstanceCache.get(caseId1);
              var instance2 = caseInstanceCache.get(caseId2);

              assertNotNull(instance1);
              assertNotNull(instance2);
              assertEquals(CaseStatus.COMPLETED, instance1.getState());
              assertEquals(CaseStatus.COMPLETED, instance2.getState());

              assertEquals(
                  instance1.getCaseMetaModel().getId(),
                  instance2.getCaseMetaModel().getId(),
                  "Both cases should share the same CaseDefinition");
            });
  }

  @Test
  public void testThreeConcurrentCasesFromSameDefinition() {
    AtomicReference<UUID> ref1 = new AtomicReference<>();
    AtomicReference<UUID> ref2 = new AtomicReference<>();
    AtomicReference<UUID> ref3 = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    try {
      ref1.set(bean.startCase(contextWith("doc-A")));
      ref2.set(bean.startCase(contextWith("doc-B")));
      ref3.set(bean.startCase(contextWith("doc-C")));
    } catch (Exception ex) {
      err.set(ex);
    }

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertNotNull(ref1.get());
              assertNotNull(ref2.get());
              assertNotNull(ref3.get());
            });

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(CaseStatus.COMPLETED, caseInstanceCache.get(ref1.get()).getState());
              assertEquals(CaseStatus.COMPLETED, caseInstanceCache.get(ref2.get()).getState());
              assertEquals(CaseStatus.COMPLETED, caseInstanceCache.get(ref3.get()).getState());
            });

    Long defId = caseInstanceCache.get(ref1.get()).getCaseMetaModel().getId();
    assertEquals(defId, caseInstanceCache.get(ref2.get()).getCaseMetaModel().getId());
    assertEquals(defId, caseInstanceCache.get(ref3.get()).getCaseMetaModel().getId());
  }

  @Test
  public void testQueryIsolationBetweenCases() {
    UUID caseId1 = startCaseAndAwait("doc-X");
    UUID caseId2 = startCaseAndAwait("doc-Y");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              String result1 = bean.query(caseId1, "documentId").toString();
              String result2 = bean.query(caseId2, "documentId").toString();

              assertNotEquals(result1, result2, "Each case should have its own context data");
            });
  }

  private UUID startCaseAndAwait(String documentId) {
    AtomicReference<UUID> ref = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    try {
      ref.set(bean.startCase(contextWith(documentId)));
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

    return ref.get();
  }

  private Map<String, Object> contextWith(String documentId) {
    return Map.of("documentId", documentId, "status", "processing");
  }
}
