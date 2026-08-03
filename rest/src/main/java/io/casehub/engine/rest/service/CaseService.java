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
package io.casehub.engine.rest.service;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.rest.exception.EntityNotFoundException;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AccessDeniedException;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclResourceType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CaseService {

  @Inject AccessControlProvider accessControlProvider;
  @Inject CurrentPrincipal currentPrincipal;

  private static final Logger LOG = Logger.getLogger(CaseService.class);
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceRepository instanceRepository;

  public CaseInstance startCase(
      String namespace,
      String name,
      String version,
      Map<String, Object> context,
      String tenancyId) {
    var metaModel =
        definitionRegistry
            .findByIdentity(namespace, name, version)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        String.format("No definition for %s/%s/%s", namespace, name, version)));

    var definition = definitionRegistry.getCaseDefinition(metaModel);
    if (definition == null) {
      throw new EntityNotFoundException(
          String.format(
              "Definition metadata exists but body not found for %s/%s/%s",
              namespace, name, version));
    }

    UUID caseId = runtime.startCase(definition, context);

    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      throw new RuntimeException("Case created (id=" + caseId + ") but not found in repository");
    }
    return instance;
  }

  public CaseInstance requireCase(UUID caseId, String tenancyId) {
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      throw new EntityNotFoundException("Case not found: " + caseId);
    }
    return instance;
  }

  public CaseInstance requireCaseAccess(UUID caseId, AclAction action) {
    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      throw new EntityNotFoundException("Case not found: " + caseId);
    }
    String actorId = currentPrincipal.actorId();
    String resourceId = AclResourceType.CASE + ":" + caseId;
    if (!accessControlProvider.canAccess(actorId, resourceId, action)) {
      LOG.warnf("ACL denied: actor=%s resource=%s action=%s", actorId, resourceId, action);
      throw new AccessDeniedException(actorId, resourceId, action);
    }
    return instance;
  }
}
