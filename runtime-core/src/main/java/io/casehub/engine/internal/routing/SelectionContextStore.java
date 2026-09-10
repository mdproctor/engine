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

import io.casehub.engine.common.spi.event.SelectionContext;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SelectionContextStore {

  private final ConcurrentHashMap<String, SelectionContext> contexts = new ConcurrentHashMap<>();

  public void store(UUID caseId, String workerName, SelectionContext context) {
    contexts.put(key(caseId, workerName), context);
  }

  public SelectionContext remove(UUID caseId, String workerName) {
    return contexts.remove(key(caseId, workerName));
  }

  private static String key(UUID caseId, String workerName) {
    return caseId + ":" + workerName;
  }
}
