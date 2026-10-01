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

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.CaseStatusChanged;

public class CbrCacheEvictionHandler {

  private final CbrRetrievalService cbrRetrievalService;

  public CbrCacheEvictionHandler(CbrRetrievalService cbrRetrievalService) {
    this.cbrRetrievalService = cbrRetrievalService;
  }

  public void handle(CaseStatusChanged event) {
    if (CaseStatus.valueOf(event.newStatus()).isTerminal()) {
      cbrRetrievalService.evict(event.instance().getUuid());
    }
  }
}
