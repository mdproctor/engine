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
package io.casehub.persistence.memory;

import io.casehub.api.model.OnThresholdReached;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.ReactiveSubCaseGroupRepository;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;

/**
 * Reactive mirror of {@link InMemorySubCaseGroupRepository}. Delegates all operations to the
 * blocking canonical and wraps results in {@code Uni}.
 *
 * <p>Delegate is injected by SPI interface (not concrete class) to avoid Quarkus ARC
 * {@code @Alternative} resolution issues.
 *
 * @see InMemorySubCaseGroupRepository
 */
@Alternative
@ApplicationScoped
public class InMemoryReactiveSubCaseGroupRepository implements ReactiveSubCaseGroupRepository {

  @Inject SubCaseGroupRepository delegate;

  public void setDelegate(InMemorySubCaseGroupRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public Uni<SubCaseGroup> getOrCreate(
      UUID parentCaseId,
      String groupId,
      int totalInGroup,
      int requiredCount,
      OnThresholdReached onThresholdReached,
      String tenancyId) {
    return Uni.createFrom()
        .item(
            delegate.getOrCreate(
                parentCaseId, groupId, totalInGroup, requiredCount, onThresholdReached, tenancyId));
  }

  @Override
  public Uni<SubCaseGroup> registerChild(
      UUID parentCaseId, String groupId, UUID childCaseId, String tenancyId) {
    try {
      return Uni.createFrom()
          .item(delegate.registerChild(parentCaseId, groupId, childCaseId, tenancyId));
    } catch (IllegalStateException e) {
      return Uni.createFrom().failure(e);
    }
  }

  @Override
  public Uni<SubCaseGroup> incrementCompleted(UUID parentCaseId, String groupId, String tenancyId) {
    try {
      return Uni.createFrom().item(delegate.incrementCompleted(parentCaseId, groupId, tenancyId));
    } catch (IllegalStateException e) {
      return Uni.createFrom().failure(e);
    }
  }

  @Override
  public Uni<SubCaseGroup> incrementRejected(UUID parentCaseId, String groupId, String tenancyId) {
    try {
      return Uni.createFrom().item(delegate.incrementRejected(parentCaseId, groupId, tenancyId));
    } catch (IllegalStateException e) {
      return Uni.createFrom().failure(e);
    }
  }

  @Override
  public Uni<Boolean> markPolicyTriggered(UUID parentCaseId, String groupId, String tenancyId) {
    return Uni.createFrom().item(delegate.markPolicyTriggered(parentCaseId, groupId, tenancyId));
  }

  @Override
  public Uni<Optional<SubCaseGroup>> findByChildCaseId(UUID childCaseId, String tenancyId) {
    return Uni.createFrom().item(delegate.findByChildCaseId(childCaseId, tenancyId));
  }
}
