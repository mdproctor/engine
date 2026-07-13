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
package io.casehub.examples.typedcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreContractTest;
import io.casehub.api.context.ContextChangeEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditingCaseContextStoreTest extends CaseContextStoreContractTest {

  @Override
  protected CaseContextStore createStore() {
    return new AuditingCaseContextStore();
  }

  @Test
  void auditLogCapturesWrites() {
    AuditingCaseContextStore store = new AuditingCaseContextStore();
    store.put("k", "v1");
    store.put("k", "v2");

    List<ContextChangeEvent> log = store.getAuditLog();
    assertEquals(2, log.size());

    assertEquals("k", log.get(0).key());
    assertNull(log.get(0).oldValue());
    assertEquals("v1", log.get(0).newValue());

    assertEquals("k", log.get(1).key());
    assertEquals("v1", log.get(1).oldValue());
    assertEquals("v2", log.get(1).newValue());
  }

  @Test
  void auditLogCapturesRemoves() {
    AuditingCaseContextStore store = new AuditingCaseContextStore();
    store.put("k", "v");
    store.remove("k");

    List<ContextChangeEvent> log = store.getAuditLog();
    assertEquals(2, log.size());
    assertEquals("k", log.get(1).key());
    assertEquals("v", log.get(1).oldValue());
    assertNull(log.get(1).newValue());
  }

  @Test
  void auditLogDoesNotRecordAbsentRemoves() {
    AuditingCaseContextStore store = new AuditingCaseContextStore();
    store.remove("absent");

    assertEquals(0, store.getAuditLog().size());
  }
}
