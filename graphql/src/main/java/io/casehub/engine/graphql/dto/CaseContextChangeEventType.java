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
package io.casehub.engine.graphql.dto;

import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.platform.graphql.scalar.Json;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.graphql.Type;

@Type("CaseContextChangeEvent")
public record CaseContextChangeEventType(UUID caseId, String changedLayer, Json contextSnapshot) {

  public static CaseContextChangeEventType from(CaseContextChangedEvent event) {
    Map<String, Object> snapshot =
        event.contextSnapshot() != null ? event.contextSnapshot().getData() : Map.of();
    return new CaseContextChangeEventType(
        event.instance().getUuid(), event.changedLayer(), Json.of(snapshot));
  }
}
