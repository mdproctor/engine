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

import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.engine.common.spi.ReactiveCaseMetaModelRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

/**
 * Reactive mirror of {@link InMemoryCaseMetaModelRepository}. Delegates all operations to the
 * blocking canonical and wraps results in {@code Uni}.
 *
 * <p>Delegate is injected by SPI interface (not concrete class) to avoid Quarkus ARC
 * {@code @Alternative} resolution issues.
 *
 * @see InMemoryCaseMetaModelRepository
 */
@Alternative
@ApplicationScoped
public class InMemoryReactiveCaseMetaModelRepository implements ReactiveCaseMetaModelRepository {

  @Inject CaseMetaModelRepository delegate;

  public void setDelegate(InMemoryCaseMetaModelRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public Uni<CaseMetaModel> findByKey(
      String namespace, String name, String version, String tenancyId) {
    CaseMetaModel result = delegate.findByKey(namespace, name, version, tenancyId);
    return result == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(result);
  }

  @Override
  public Uni<CaseMetaModel> save(CaseMetaModel metaModel, String tenancyId) {
    return Uni.createFrom().item(delegate.save(metaModel, tenancyId));
  }
}
