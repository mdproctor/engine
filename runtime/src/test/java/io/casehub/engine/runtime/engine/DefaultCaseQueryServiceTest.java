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
package io.casehub.engine.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.engine.CaseSummary;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultCaseQueryServiceTest {

  private InMemoryRepo repo;
  private DefaultCaseQueryService service;

  @BeforeEach
  void setUp() {
    repo = new InMemoryRepo();
    service = new DefaultCaseQueryService();
    service.repository = repo;
  }

  @Test
  void countActive_returnsOnlyNonTerminalNonSuspendedCases() {
    repo.cases.add(caseWith("t1", CaseStatus.RUNNING, "ns", "order"));
    repo.cases.add(caseWith("t1", CaseStatus.WAITING, "ns", "order"));
    repo.cases.add(caseWith("t1", CaseStatus.STARTING, "ns", "order"));
    repo.cases.add(caseWith("t1", CaseStatus.SUSPENDED, "ns", "order"));
    repo.cases.add(caseWith("t1", CaseStatus.COMPLETED, "ns", "order"));
    repo.cases.add(caseWith("t1", CaseStatus.FAULTED, "ns", "order"));
    repo.cases.add(caseWith("t1", CaseStatus.CANCELLED, "ns", "order"));

    assertThat(service.countActive("t1")).isEqualTo(3);
  }

  @Test
  void countActive_isolatesByTenant() {
    repo.cases.add(caseWith("t1", CaseStatus.RUNNING, "ns", "a"));
    repo.cases.add(caseWith("t2", CaseStatus.RUNNING, "ns", "b"));

    assertThat(service.countActive("t1")).isEqualTo(1);
    assertThat(service.countActive("t2")).isEqualTo(1);
  }

  @Test
  void listActive_returnsSummaries() {
    repo.cases.add(caseWith("t1", CaseStatus.RUNNING, "casehub", "incident"));
    repo.cases.add(caseWith("t1", CaseStatus.COMPLETED, "casehub", "done"));

    List<CaseSummary> result = service.listActive("t1", 0, 20);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).namespace()).isEqualTo("casehub");
    assertThat(result.get(0).name()).isEqualTo("incident");
    assertThat(result.get(0).status()).isEqualTo(CaseStatus.RUNNING);
  }

  private static CaseInstance caseWith(
      String tenancyId, CaseStatus status, String namespace, String name) {
    CaseInstance ci = new CaseInstance();
    ci.tenancyId = tenancyId;
    ci.setUuid(UUID.randomUUID());
    ci.setState(status);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace(namespace);
    meta.setName(name);
    ci.setCaseMetaModel(meta);
    return ci;
  }

  static class InMemoryRepo implements CaseInstanceRepository {
    final List<CaseInstance> cases = new ArrayList<>();

    @Override
    public CaseInstance save(CaseInstance instance, String tenancyId) {
      cases.add(instance);
      return instance;
    }

    @Override
    public CaseInstance update(CaseInstance instance, String tenancyId) {
      return instance;
    }

    @Override
    public Optional<CaseInstance> findByUuid(UUID uuid, String tenancyId) {
      return cases.stream()
          .filter(c -> c.getUuid().equals(uuid) && tenancyId.equals(c.tenancyId))
          .findFirst();
    }

    @Override
    public void updateStateAndAppendEvent(
        CaseInstance instance, EventLog eventLog, String tenancyId) {}

    @Override
    public long count(CaseInstanceQuery query, String tenancyId) {
      return cases.stream()
          .filter(c -> tenancyId.equals(c.tenancyId))
          .filter(c -> query.status() == null || c.getState() == query.status())
          .count();
    }

    @Override
    public List<CaseInstance> query(CaseInstanceQuery query, String tenancyId) {
      return cases.stream()
          .filter(c -> tenancyId.equals(c.tenancyId))
          .filter(c -> query.status() == null || c.getState() == query.status())
          .skip((long) query.page() * query.size())
          .limit(query.size())
          .toList();
    }
  }
}
