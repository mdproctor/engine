package io.casehub.engine.rest.service;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.EngineCaseDefinitionApi;
import io.casehub.api.view.CaseDefinitionPage;
import io.casehub.api.view.CaseDefinitionView;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.engine.common.spi.query.CaseDefinitionQuery;
import io.casehub.engine.rest.exception.EntityNotFoundException;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.worker.api.Capability;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class DefaultEngineCaseDefinitionApi implements EngineCaseDefinitionApi {

  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject AccessControlProvider accessControlProvider;

  @Override
  public CaseDefinitionPage listDefinitions(String tenancyId, Integer offset, Integer limit) {
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    int page = offset != null ? offset : 0;
    int size = limit != null ? limit : 20;
    var query = CaseDefinitionQuery.builder()
        .page(page)
        .size(size)
        .build();
    var metas = metaModelRepository.query(query, resolvedTenancyId);
    String actorId = currentPrincipal.actorId();
    var items = metas.stream()
        .filter(meta -> accessControlProvider.canAccess(
            actorId,
            new ResourceId(io.casehub.api.acl.EngineResourceTypes.CASE_DEFINITION,
                meta.getNamespace() + "/" + meta.getName() + "/" + meta.getVersion()),
            AclAction.READ))
        .map(meta -> {
          CaseDefinition def = definitionRegistry.getCaseDefinition(meta);
          return mapDefinition(meta, def);
        })
        .filter(Objects::nonNull)
        .toList();
    return new CaseDefinitionPage(items, items.size(), false);
  }

  @Override
  public List<CaseDefinitionView> getDefinitionsByName(
      String namespace, String name, String tenancyId) {
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    var query = CaseDefinitionQuery.builder()
        .namespace(namespace)
        .name(name)
        .build();
    return metaModelRepository.query(query, resolvedTenancyId).stream()
        .map(meta -> {
          CaseDefinition def = definitionRegistry.getCaseDefinition(meta);
          return mapDefinition(meta, def);
        })
        .filter(Objects::nonNull)
        .toList();
  }

  @Override
  public CaseDefinitionView getDefinitionByKey(
      String namespace, String name, String version, String tenancyId) {
    var meta = definitionRegistry.findByIdentity(namespace, name, version)
        .orElseThrow(() -> new EntityNotFoundException(
            String.format("No definition for %s/%s/%s", namespace, name, version)));
    CaseDefinition def = definitionRegistry.getCaseDefinition(meta);
    if (def == null) {
      throw new EntityNotFoundException(
          String.format("Definition body not found for %s/%s/%s", namespace, name, version));
    }
    return mapDefinition(meta, def);
  }

  private CaseDefinitionView mapDefinition(CaseMetaModel meta, CaseDefinition def) {
    if (def == null) {
      return null;
    }
    List<String> capabilities = def.getCapabilities() != null
        ? def.getCapabilities().stream().map(Capability::name).toList()
        : List.of();
    return new CaseDefinitionView(
        meta.getNamespace(),
        meta.getName(),
        meta.getVersion(),
        def.getTitle(),
        def.getSummary(),
        capabilities);
  }
}
