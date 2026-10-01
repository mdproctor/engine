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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.CorpusChangeEvent;
import io.casehub.api.spi.CorpusSourceAdapter;
import io.casehub.api.spi.ResolutionGuideInput;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrGuidanceRecord;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRecord;
import io.casehub.neocortex.memory.cbr.CbrRecordSchema;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.platform.api.path.Path;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResolutionIngestionServiceTest {

  private RecordingStore store;
  private ResolutionIngestionService service;

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
  }

  @Test
  void ingestsDiscoveredDocuments() {
    var adapter =
        testAdapter(
            List.of(
                new ResolutionGuideInput(
                    "doc-1",
                    "Phishing runbook",
                    "1. Isolate 2. Reset",
                    null,
                    Map.of("category", (Object) "phishing"),
                    "soc-domain",
                    null)));

    service = buildService(adapter);
    service.ingest("tenant-1");

    assertThat(store.storedCases).hasSize(1);
    assertThat(store.storedCases.get(0)).isInstanceOf(CbrGuidanceRecord.class);
    var guide = (CbrGuidanceRecord) store.storedCases.get(0);
    assertThat(guide.problem()).isEqualTo("Phishing runbook");
    assertThat(guide.solution()).isEqualTo("1. Isolate 2. Reset");
    assertThat(store.storedDomains.get(0).name()).isEqualTo("soc-domain");
    assertThat(store.storedTenantIds.get(0)).isEqualTo("tenant-1");
  }

  @Test
  void idempotentOnRestart() {
    var adapter =
        testAdapter(
            List.of(
                new ResolutionGuideInput(
                    "doc-1", "Problem", "Solution", null, Map.of(), "domain", null)));

    service = buildService(adapter);
    service.ingest("tenant-1");
    service.ingest("tenant-1");

    assertThat(store.storedCases).hasSize(2);
    assertThat(store.supersededIds)
        .contains(ResolutionIngestionService.deterministicCaseId("doc-1"));
  }

  @Test
  void errorIsolationPerDocument() {
    var adapter =
        testAdapter(
            List.of(
                new ResolutionGuideInput("bad-1", "", "sol", null, Map.of(), "d", null),
                new ResolutionGuideInput(
                    "good-1", "Problem", "Solution", null, Map.of(), "d", null)));

    service = buildService(adapter);
    service.ingest("tenant-1");

    assertThat(store.storedCases).hasSize(1);
    assertThat(((CbrGuidanceRecord) store.storedCases.get(0)).problem()).isEqualTo("Problem");
  }

  @Test
  void handleChangeAdded() {
    service = buildService(testAdapter(List.of()));
    var input =
        new ResolutionGuideInput(
            "new-doc", "New problem", "New solution", null, Map.of(), "domain", null);

    service.handleChange(new CorpusChangeEvent.Added(input), "tenant-1");

    assertThat(store.storedCases).hasSize(1);
  }

  @Test
  void handleChangeRemoved() {
    service = buildService(testAdapter(List.of()));
    service.handleChange(new CorpusChangeEvent.Removed("old-doc"), "tenant-1");

    assertThat(store.supersededIds)
        .contains(ResolutionIngestionService.deterministicCaseId("old-doc"));
  }

  @Test
  void deterministicCaseIdIsStable() {
    String id1 = ResolutionIngestionService.deterministicCaseId("doc-1");
    String id2 = ResolutionIngestionService.deterministicCaseId("doc-1");
    assertThat(id1).isEqualTo(id2);

    String id3 = ResolutionIngestionService.deterministicCaseId("doc-2");
    assertThat(id1).isNotEqualTo(id3);
  }

  private ResolutionIngestionService buildService(CorpusSourceAdapter adapter) {
    @SuppressWarnings("unchecked")
    Instance<CorpusSourceAdapter> adapterInstance = org.mockito.Mockito.mock(Instance.class);
    org.mockito.Mockito.when(adapterInstance.isResolvable()).thenReturn(true);
    org.mockito.Mockito.when(adapterInstance.get()).thenReturn(adapter);

    @SuppressWarnings("unchecked")
    Instance<CbrRecordStore> storeInstance = org.mockito.Mockito.mock(Instance.class);
    org.mockito.Mockito.when(storeInstance.isResolvable()).thenReturn(true);
    org.mockito.Mockito.when(storeInstance.get()).thenReturn(store);

    return new ResolutionIngestionService(adapterInstance, storeInstance);
  }

  private static CorpusSourceAdapter testAdapter(List<ResolutionGuideInput> inputs) {
    return new CorpusSourceAdapter() {
      @Override
      public String id() {
        return "test";
      }

      @Override
      public List<ResolutionGuideInput> discover(String tenancyId) {
        return inputs;
      }
    };
  }

  static class RecordingStore implements CbrRecordStore {
    final List<CbrRecord> storedCases = new ArrayList<>();
    final List<MemoryDomain> storedDomains = new ArrayList<>();
    final List<String> storedTenantIds = new ArrayList<>();
    final List<String> supersededIds = new ArrayList<>();

    @Override
    public void registerSchema(CbrRecordSchema schema) {}

    @Override
    public String store(
        CbrRecord c, String ct, String eid, MemoryDomain d, String tid, String cid, Path scope) {
      storedCases.add(c);
      storedDomains.add(d);
      storedTenantIds.add(tid);
      return cid;
    }

    @Override
    public <C extends CbrRecord> List<CbrMatch<C>> retrieveSimilar(
        CbrQuery query, Class<C> caseType) {
      return List.of();
    }

    @Override
    public List<String> findCaseIds(
        String caseType,
        MemoryDomain domain,
        String tenantId,
        java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters) {
      return List.of();
    }

    @Override
    public Integer erase(EraseRequest r) {
      return 0;
    }

    @Override
    public Integer eraseEntity(String eid, String tid) {
      return 0;
    }

    @Override
    public void recordOutcome(
        String caseId, String tenantId, io.casehub.neocortex.memory.cbr.CbrOutcome outcome) {}

    @Override
    public Integer purge(io.casehub.neocortex.memory.cbr.CbrRetentionPolicy policy) {
      return 0;
    }

    @Override
    public boolean supersede(String caseId, String tenantId, String newCaseId, String reason) {
      return true;
    }

    @Override
    public boolean reinstate(String caseId, String tenantId) {
      return true;
    }

    @Override
    public Integer eraseByScope(Path scope, String tenantId) {
      return 0;
    }

    @Override
    public int supersedeAll(Collection<String> caseIds, String tenantId, String reason) {
      supersededIds.addAll(caseIds);
      return caseIds.size();
    }

    @Override
    public int reinstateAll(Collection<String> caseIds, String tenantId) {
      return 0;
    }

    @Override
    public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(
        String caseId, String tenantId) {
      return io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED;
    }

    @Override
    public List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(
        String tenantId, MemoryDomain domain) {
      return List.of();
    }

    @Override
    public int supersedeMatching(
        String tenantId,
        MemoryDomain domain,
        String caseType,
        java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters,
        String reason) {
      return 0;
    }

    @Override
    public int reinstateMatching(
        String tenantId,
        MemoryDomain domain,
        String caseType,
        java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters) {
      return 0;
    }
  }
}
