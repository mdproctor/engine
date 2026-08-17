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

import io.casehub.api.model.CaseDefinition;
import java.util.List;
import org.eclipse.microprofile.graphql.Type;

@Type("CaseDefinitionResponse")
public record CaseDefinitionType(
    String namespace,
    String name,
    String version,
    String title,
    String summary,
    List<String> capabilities) {

  public static CaseDefinitionType from(CaseDefinition def) {
    List<String> capNames =
        def.getCapabilities() != null
            ? def.getCapabilities().stream().map(c -> c.name()).toList()
            : List.of();
    return new CaseDefinitionType(
        def.getNamespace(),
        def.getName(),
        def.getVersion(),
        def.getTitle(),
        def.getSummary(),
        capNames);
  }
}
