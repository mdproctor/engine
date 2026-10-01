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

import io.casehub.api.spi.CorpusChangeEvent;
import io.casehub.api.spi.CorpusSourceAdapter;
import io.casehub.api.spi.ResolutionGuideInput;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrGuidanceRecord;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.platform.api.path.Path;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ResolutionIngestionService {

  private static final Logger LOG = Logger.getLogger(ResolutionIngestionService.class);

  private final Instance<CorpusSourceAdapter> adapterInstance;
  private final Instance<CbrRecordStore> cbrStoreInstance;

  @Inject
  public ResolutionIngestionService(
      Instance<CorpusSourceAdapter> adapterInstance, Instance<CbrRecordStore> cbrStoreInstance) {
    this.adapterInstance = adapterInstance;
    this.cbrStoreInstance = cbrStoreInstance;
  }

  public void ingest(String tenancyId) {
    if (!adapterInstance.isResolvable() || !cbrStoreInstance.isResolvable()) {
      return;
    }
    CorpusSourceAdapter adapter = adapterInstance.get();
    CbrRecordStore store = cbrStoreInstance.get();
    List<ResolutionGuideInput> inputs = adapter.discover(tenancyId);
    int ingested = 0;
    int failed = 0;
    for (ResolutionGuideInput input : inputs) {
      try {
        ingestSingle(input, tenancyId, store);
        ingested++;
      } catch (Exception e) {
        failed++;
        LOG.warnf("Ingestion failed for document '%s': %s", input.documentId(), e.getMessage());
      }
    }
    if (ingested > 0 || failed > 0) {
      LOG.infof(
          "Corpus ingestion complete for tenant '%s': %d ingested, %d failed",
          tenancyId, ingested, failed);
    }
  }

  public void handleChange(CorpusChangeEvent event, String tenancyId) {
    if (!cbrStoreInstance.isResolvable()) {
      return;
    }
    CbrRecordStore store = cbrStoreInstance.get();
    switch (event) {
      case CorpusChangeEvent.Added a -> ingestSingle(a.input(), tenancyId, store);
      case CorpusChangeEvent.Updated u -> {
        String caseId = deterministicCaseId(u.documentId());
        store.supersedeAll(List.of(caseId), tenancyId, "corpus update");
        ingestSingle(u.input(), tenancyId, store);
      }
      case CorpusChangeEvent.Removed r -> {
        String caseId = deterministicCaseId(r.documentId());
        store.supersedeAll(List.of(caseId), tenancyId, "corpus removal");
      }
    }
  }

  private void ingestSingle(ResolutionGuideInput input, String tenancyId, CbrRecordStore store) {
    String caseId = deterministicCaseId(input.documentId());
    CbrGuidanceRecord guide =
        new CbrGuidanceRecord(input.problem(), input.solution(), null, null, null, null);
    store.supersedeAll(List.of(caseId), tenancyId, "corpus re-ingestion");
    store.store(
        guide,
        CbrGuidanceRecord.CBR_TYPE,
        input.documentId(),
        new MemoryDomain(input.domain()),
        tenancyId,
        caseId,
        Path.root());
  }

  static String deterministicCaseId(String documentId) {
    return UUID.nameUUIDFromBytes(documentId.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
