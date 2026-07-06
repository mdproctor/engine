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
package io.casehub.persistence.jpa;

import io.casehub.api.model.OnThresholdReached;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking JPA {@link SubCaseGroupRepository}. Delegates to {@link
 * JpaReactiveSubCaseGroupRepository} and awaits.
 */
@ApplicationScoped
public class JpaSubCaseGroupRepository implements SubCaseGroupRepository {

  @Inject JpaReactiveSubCaseGroupRepository delegate;

  @Override
  public SubCaseGroup getOrCreate(
      UUID parentCaseId,
      String groupId,
      int totalInGroup,
      int requiredCount,
      OnThresholdReached onThresholdReached,
      String tenancyId) {
    return delegate
        .getOrCreate(
            parentCaseId, groupId, totalInGroup, requiredCount, onThresholdReached, tenancyId)
        .await()
        .indefinitely();
  }

  @Override
  public SubCaseGroup registerChild(
      UUID parentCaseId, String groupId, UUID childCaseId, String tenancyId) {
    return delegate
        .registerChild(parentCaseId, groupId, childCaseId, tenancyId)
        .await()
        .indefinitely();
  }

  @Override
  public SubCaseGroup incrementCompleted(UUID parentCaseId, String groupId, String tenancyId) {
    return delegate.incrementCompleted(parentCaseId, groupId, tenancyId).await().indefinitely();
  }

  @Override
  public SubCaseGroup incrementRejected(UUID parentCaseId, String groupId, String tenancyId) {
    return delegate.incrementRejected(parentCaseId, groupId, tenancyId).await().indefinitely();
  }

  @Override
  public boolean markPolicyTriggered(UUID parentCaseId, String groupId, String tenancyId) {
    return delegate.markPolicyTriggered(parentCaseId, groupId, tenancyId).await().indefinitely();
  }

  @Override
  public Optional<SubCaseGroup> findByChildCaseId(UUID childCaseId, String tenancyId) {
    return delegate.findByChildCaseId(childCaseId, tenancyId).await().indefinitely();
  }
}
