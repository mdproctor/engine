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
package io.casehub.api.spi.improvement;

import io.casehub.api.model.stigmergy.HilQueueEntry;
import io.casehub.api.model.stigmergy.ResearchAnalysis;
import io.casehub.api.model.stigmergy.ResearchCandidate;
import io.casehub.api.model.stigmergy.ResearchFinding;
import java.util.List;
import java.util.Optional;

public interface ResearchCorpus {

  void store(List<ResearchCandidate> candidates, ResearchAnalysis analysis);

  List<ResearchFinding> search(String query, String capabilityArea, int limit);

  Optional<ResearchFinding> get(String sourceUrl);

  List<HilQueueEntry> pendingHilEntries();

  void addToHilQueue(HilQueueEntry entry);

  void resolveHilEntry(String sourceUrl, ResearchCandidate retrieved);
}
