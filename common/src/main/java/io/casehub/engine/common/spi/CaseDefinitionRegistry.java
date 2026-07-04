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
package io.casehub.engine.common.spi;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.smallrye.mutiny.Uni;
import java.util.Optional;

/**
 * Registry for case definitions.
 *
 * <p>This interface defines the contract for managing case definitions in the Case Hub engine. It
 * provides methods to:
 *
 * <ul>
 *   <li>Register new case definitions and persist their metadata
 *   <li>Retrieve case definitions by their metadata
 *   <li>Lookup metadata for a given case definition
 * </ul>
 *
 * <p>Implementations are responsible for:
 *
 * <ul>
 *   <li>Validating case definitions before registration
 *   <li>Persisting case metadata via {@link CaseMetaModelRepository}
 *   <li>Maintaining an in-memory cache for fast lookup
 * </ul>
 *
 * @see CaseDefinition
 * @see CaseMetaModel
 */
public interface CaseDefinitionRegistry {

  /**
   * Register a case definition.
   *
   * <p>Validates the definition's expressions and persists its metadata to the repository. If a
   * definition with the same namespace/name/version already exists, returns the existing metadata.
   *
   * @param model the case definition to register
   * @return Uni containing the persisted CaseMetaModel
   * @throws IllegalArgumentException if validation fails (wrapped in Uni.failure)
   */
  Uni<CaseMetaModel> registerCaseDefinition(CaseDefinition model);

  /**
   * Retrieve a case definition by its metadata.
   *
   * @param definition the case metadata
   * @return the case definition, or null if not found
   */
  CaseDefinition getCaseDefinition(CaseMetaModel definition);

  /**
   * Lookup metadata for a case definition.
   *
   * @param caseDefinition the case definition
   * @return the corresponding CaseMetaModel
   * @throws RuntimeException if no metadata is found
   */
  CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition);

  /**
   * Find metadata by identity coordinates without throwing on not-found.
   *
   * @param namespace the case definition namespace
   * @param name the case definition name
   * @param version the case definition version
   * @return Optional containing the CaseMetaModel if registered, empty otherwise
   */
  default Optional<CaseMetaModel> findByIdentity(String namespace, String name, String version) {
    return Optional.empty();
  }

  /**
   * Find a case definition by name only (without namespace or version).
   *
   * <p>This is a convenience lookup for {@link
   * io.casehub.api.engine.WorkerRuntime#spawnCase(String, java.util.Map)} where the caller knows
   * the case name but not the full identity coordinates.
   *
   * @param name the case definition name
   * @return Optional containing the CaseDefinition if exactly one match exists, empty otherwise
   * @throws IllegalArgumentException if multiple definitions share the same name across namespaces
   */
  default Optional<CaseDefinition> findByName(String name) {
    return Optional.empty();
  }
}
