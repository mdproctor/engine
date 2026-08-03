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
package io.casehub.engine.internal.acl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerIdentityResolverTest {

  private final WorkerIdentityResolver resolver = new WorkerIdentityResolver();

  @Test
  void resolve_withServiceAccountId_usesIt() {
    UUID caseId = UUID.randomUUID();
    var identity = resolver.resolve("agent:pool-1@acme.io", caseId);
    assertEquals("agent:pool-1@acme.io", identity.actorId());
    assertFalse(identity.ephemeral());
  }

  @Test
  void resolve_withoutServiceAccountId_mintsEphemeral() {
    UUID caseId = UUID.randomUUID();
    var identity = resolver.resolve(null, caseId);
    assertTrue(identity.actorId().startsWith("agent:worker-"));
    assertTrue(identity.actorId().contains(caseId.toString().substring(0, 8)));
    assertTrue(identity.ephemeral());
  }

  @Test
  void resolve_ephemeralIdentitiesAreUnique() {
    UUID caseId = UUID.randomUUID();
    var id1 = resolver.resolve(null, caseId);
    var id2 = resolver.resolve(null, caseId);
    assertNotEquals(id1.actorId(), id2.actorId());
  }
}
