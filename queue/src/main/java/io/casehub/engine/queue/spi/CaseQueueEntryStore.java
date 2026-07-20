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
package io.casehub.engine.queue.spi;

import io.casehub.engine.queue.model.CaseQueueEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseQueueEntryStore {

  CaseQueueEntry save(CaseQueueEntry entry);

  CaseQueueEntry upsertByCaseAndView(CaseQueueEntry entry);

  Optional<CaseQueueEntry> findById(UUID id);

  Optional<CaseQueueEntry> findByCaseAndView(UUID caseId, UUID viewId);

  List<CaseQueueEntry> findByView(UUID viewId, String tenancyId);

  List<CaseQueueEntry> findByCaseId(UUID caseId);

  long countByView(UUID viewId, String tenancyId);

  boolean delete(UUID id);

  void deleteByCaseId(UUID caseId);

  Optional<CaseQueueEntry> claimIfPending(UUID entryId, String userId);
}
