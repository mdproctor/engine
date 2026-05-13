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
package io.casehub.engine.spi;

import io.casehub.api.model.OnThresholdReached;
import io.casehub.engine.internal.model.SubCaseGroup;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.UUID;

public interface SubCaseGroupRepository {

  Uni<SubCaseGroup> getOrCreate(
      UUID parentCaseId,
      String groupId,
      int totalInGroup,
      int requiredCount,
      OnThresholdReached onThresholdReached);

  Uni<SubCaseGroup> registerChild(UUID parentCaseId, String groupId, UUID childCaseId);

  Uni<SubCaseGroup> incrementCompleted(UUID parentCaseId, String groupId);

  Uni<SubCaseGroup> incrementRejected(UUID parentCaseId, String groupId);

  /** Returns {@code true} if this call actually set the flag; {@code false} if already set. */
  Uni<Boolean> markPolicyTriggered(UUID parentCaseId, String groupId);

  Uni<Optional<SubCaseGroup>> findByChildCaseId(UUID childCaseId);
}
