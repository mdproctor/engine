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
package io.casehub.engine.rest;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class TestCaseDefinitionRegistry implements CaseDefinitionRegistry {

  private final Map<String, CaseDefinition> definitions = new ConcurrentHashMap<>();
  private final Map<String, CaseMetaModel> metaModels = new ConcurrentHashMap<>();

  public void register(CaseDefinition definition, CaseMetaModel metaModel) {
    String key = key(metaModel.getNamespace(), metaModel.getName(), metaModel.getVersion());
    definitions.put(key, definition);
    metaModels.put(key, metaModel);
  }

  @Override
  public CaseMetaModel registerCaseDefinition(CaseDefinition model) {
    return null;
  }

  @Override
  public CaseDefinition getCaseDefinition(CaseMetaModel definition) {
    String key = key(definition.getNamespace(), definition.getName(), definition.getVersion());
    return definitions.get(key);
  }

  @Override
  public CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition) {
    return metaModels.values().stream()
        .filter(
            m -> {
              String key = key(m.getNamespace(), m.getName(), m.getVersion());
              return definitions.get(key) == caseDefinition;
            })
        .findFirst()
        .orElseThrow();
  }

  @Override
  public Optional<CaseMetaModel> findByIdentity(String namespace, String name, String version) {
    return Optional.ofNullable(metaModels.get(key(namespace, name, version)));
  }

  private String key(String namespace, String name, String version) {
    return namespace + ":" + name + ":" + version;
  }
}
