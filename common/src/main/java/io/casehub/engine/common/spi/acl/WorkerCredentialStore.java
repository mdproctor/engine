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
package io.casehub.engine.common.spi.acl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerCredentialStore {

  void store(WorkerCredential credential);

  Optional<WorkerCredential> lookup(String token);

  void revoke(String token);

  List<WorkerCredential> revokeByCase(UUID caseId);

  List<WorkerCredential> revokeByActor(String actorId);

  List<WorkerCredential> findActiveByActorAndCase(String actorId, UUID caseId);
}
