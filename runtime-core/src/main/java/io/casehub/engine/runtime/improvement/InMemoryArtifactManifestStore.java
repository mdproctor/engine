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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.ArtifactEntry;
import io.casehub.api.model.stigmergy.ArtifactManifest;
import io.casehub.engine.common.spi.ArtifactManifestStore;
import io.casehub.engine.common.spi.Resettable;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryArtifactManifestStore implements ArtifactManifestStore, Resettable {

  private final ConcurrentHashMap<String, List<ArtifactEntry>> entries = new ConcurrentHashMap<>();

  @Override
  public void addEntry(UUID caseId, UUID improvementCaseId, ArtifactEntry entry, String tenancyId) {
    entries.computeIfAbsent(key(caseId, improvementCaseId), k -> new ArrayList<>()).add(entry);
  }

  @Override
  public ArtifactManifest find(UUID caseId, UUID improvementCaseId, String tenancyId) {
    var list = entries.get(key(caseId, improvementCaseId));
    if (list == null || list.isEmpty()) return null;
    return new ArtifactManifest(improvementCaseId, List.copyOf(list));
  }

  @Override
  public void reset() {
    entries.clear();
  }

  private static String key(UUID caseId, UUID improvementCaseId) {
    return caseId.toString() + ":" + improvementCaseId.toString();
  }
}
