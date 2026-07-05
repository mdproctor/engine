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
package io.casehub.api.model;

import java.util.Objects;

public record EpisodicMemoryConfig(
    String domain, // MemoryDomain name
    String entityId, // JQ expression against semantic layer; result -> List<String>
    int recent // max items; default 10
    ) {
  public EpisodicMemoryConfig {
    Objects.requireNonNull(domain, "domain is required");
    Objects.requireNonNull(entityId, "entityId is required");
    if (recent < 1) recent = 10;
  }

  public static EpisodicMemoryConfig of(String domain, String entityId) {
    return new EpisodicMemoryConfig(domain, entityId, 10);
  }

  public static EpisodicMemoryConfig of(String domain, String entityId, int recent) {
    return new EpisodicMemoryConfig(domain, entityId, recent);
  }
}
