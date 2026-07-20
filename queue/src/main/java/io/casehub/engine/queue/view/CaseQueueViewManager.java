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
package io.casehub.engine.queue.view;

import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.view.SubjectViewOrchestrator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class CaseQueueViewManager {

  private final SubjectViewOrchestrator views;

  @Inject
  public CaseQueueViewManager(SubjectViewOrchestrator views) {
    this.views = views;
  }

  public SubjectViewSpec ensureQueueView(String name, String tenancyId, String labelPattern) {
    UUID viewId = UUID.nameUUIDFromBytes((tenancyId + ":" + name).getBytes(StandardCharsets.UTF_8));
    SubjectViewSpec spec =
        new SubjectViewSpec(
            viewId, name, tenancyId, labelPattern, null, "createdAt", "ASC", null, Instant.now());
    return views.saveView(spec);
  }

  public boolean deleteQueueView(UUID viewId) {
    return views.deleteView(viewId);
  }
}
