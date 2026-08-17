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
package io.casehub.engine.graphql;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.graphql.dto.CaseControl;
import io.casehub.engine.graphql.dto.CaseInstanceType;
import io.casehub.engine.graphql.dto.SignalResult;
import io.casehub.engine.graphql.dto.StartCaseInput;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;

@GraphQLApi
@McpDomain("engine")
@ApplicationScoped
public class CaseMutationResolver {

  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceRepository instanceRepository;
  @Inject CurrentPrincipal currentPrincipal;

  @Mutation
  @Description("Start a new case from a registered definition with optional initial context")
  public CaseInstanceType startCase(StartCaseInput input) {
    var metaModel =
        definitionRegistry
            .findByIdentity(input.namespace(), input.name(), input.version())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No definition for "
                            + input.namespace()
                            + "/"
                            + input.name()
                            + "/"
                            + input.version()));

    var definition = definitionRegistry.getCaseDefinition(metaModel);
    Map<String, Object> context = input.context() != null ? input.context().value() : Map.of();

    UUID caseId = runtime.startCase(definition, context);

    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      throw new RuntimeException("Case created (id=" + caseId + ") but not found in repository");
    }
    return CaseInstanceType.from(instance);
  }

  @Mutation
  @Description("Send a signal to a running case at a specific context path")
  public SignalResult signalCase(UUID caseId, String path, String value) {
    Object signalValue = value;
    runtime.signal(caseId, path, signalValue);
    return new SignalResult(caseId, true);
  }

  @Mutation
  @Description("Suspend a running case — pauses all active plan items")
  public CaseControl suspendCase(UUID caseId) {
    runtime.suspendCase(caseId);
    CaseInstance instance = instanceRepository.findByUuid(caseId, currentPrincipal.tenancyId());
    return new CaseControl(caseId, instance != null ? instance.getState() : null);
  }

  @Mutation
  @Description("Resume a previously suspended case")
  public CaseControl resumeCase(UUID caseId) {
    runtime.resumeCase(caseId);
    CaseInstance instance = instanceRepository.findByUuid(caseId, currentPrincipal.tenancyId());
    return new CaseControl(caseId, instance != null ? instance.getState() : null);
  }

  @Mutation
  @Description("Cancel a case — terminally closes it and all active plan items")
  public CaseControl cancelCase(UUID caseId) {
    runtime.cancelCase(caseId);
    CaseInstance instance = instanceRepository.findByUuid(caseId, currentPrincipal.tenancyId());
    return new CaseControl(caseId, instance != null ? instance.getState() : null);
  }
}
