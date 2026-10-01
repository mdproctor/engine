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
package io.casehub.engine.internal.engine;

import io.casehub.api.engine.CaseQueryService;
import io.casehub.api.engine.CaseSummary;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class DefaultCaseQueryService implements CaseQueryService {

  @Inject CaseInstanceRepository repository;

  @Override
  public long countActive(String tenancyId) {
    long count = 0;
    for (CaseStatus status : List.of(CaseStatus.STARTING, CaseStatus.RUNNING, CaseStatus.WAITING)) {
      count += repository.count(CaseInstanceQuery.builder().status(status).build(), tenancyId);
    }
    return count;
  }

  @Override
  public List<CaseSummary> listActive(String tenancyId, int page, int size) {
    List<CaseInstance> all = new ArrayList<>();
    for (CaseStatus status : List.of(CaseStatus.STARTING, CaseStatus.RUNNING, CaseStatus.WAITING)) {
      all.addAll(
          repository.query(
              CaseInstanceQuery.builder().status(status).size(Integer.MAX_VALUE).build(),
              tenancyId));
    }
    int start = page * size;
    List<CaseInstance> paged =
        start >= all.size() ? List.of() : all.subList(start, Math.min(start + size, all.size()));
    return paged.stream()
        .map(
            ci ->
                new CaseSummary(
                    ci.getUuid(),
                    ci.getCaseMetaModel() != null ? ci.getCaseMetaModel().getNamespace() : null,
                    ci.getCaseMetaModel() != null ? ci.getCaseMetaModel().getName() : null,
                    ci.getState()))
        .toList();
  }
}
