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
package io.casehub.engine.common.internal.event;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.Objects;

public record CaseContextChangedEvent(CaseInstance instance, JsonNode contextSnapshot) {

  public CaseContextChangedEvent {
    instance = Objects.requireNonNull(instance, "instance cannot be null");
    contextSnapshot =
        Objects.requireNonNull(contextSnapshot, "contextSnapshot cannot be null").deepCopy();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CaseContextChangedEvent that = (CaseContextChangedEvent) o;
    return Objects.equals(instance, that.instance)
        && Objects.equals(contextSnapshot, that.contextSnapshot);
  }

  @Override
  public String toString() {
    return "CaseStateContextChangedEvent{" + "uuid=" + instance.getUuid() + '}';
  }
}
