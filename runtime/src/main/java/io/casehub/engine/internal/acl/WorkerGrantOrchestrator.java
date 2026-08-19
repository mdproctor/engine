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
package io.casehub.engine.internal.acl;

import io.casehub.api.acl.EngineAuthorizationContext;
import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclEntryRequest;
import io.casehub.platform.api.acl.AuthorizationDecision;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.acl.WorkerAction;
import io.casehub.platform.api.acl.WorkerAuthorizationDeniedException;
import io.casehub.platform.api.acl.WorkerAuthorizationPolicy;
import io.casehub.platform.api.acl.WorkerCredential;
import io.casehub.platform.api.acl.WorkerCredentialStore;
import io.casehub.platform.api.acl.WorkerPermissionRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkerGrantOrchestrator {

  private static final Logger LOG = Logger.getLogger(WorkerGrantOrchestrator.class);
  private static final Duration MAX_CREDENTIAL_TTL = Duration.ofHours(1);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final AccessControlProvider accessControlProvider;
  private final WorkerCredentialStore credentialStore;
  private final WorkerIdentityResolver identityResolver;
  private final WorkerAuthorizationPolicy authorizationPolicy;

  @Inject
  public WorkerGrantOrchestrator(
      AccessControlProvider accessControlProvider,
      WorkerCredentialStore credentialStore,
      WorkerIdentityResolver identityResolver,
      WorkerAuthorizationPolicy authorizationPolicy) {
    this.accessControlProvider = accessControlProvider;
    this.credentialStore = credentialStore;
    this.identityResolver = identityResolver;
    this.authorizationPolicy = authorizationPolicy;
  }

  public WorkerCredential grantAndMint(
      String serviceAccountId,
      List<WorkerAction> actions,
      UUID caseId,
      String tenancyId,
      Instant deadline,
      String caseDefinitionId) {
    var identity = identityResolver.resolve(serviceAccountId, caseId);

    var permissionRequest =
        new WorkerPermissionRequest(
            identity.actorId(),
            EngineResourceTypes.CASE,
            Set.copyOf(actions),
            new EngineAuthorizationContext(caseDefinitionId),
            tenancyId);
    AuthorizationDecision decision = authorizationPolicy.evaluate(permissionRequest);
    if (!decision.approved()) {
      throw new WorkerAuthorizationDeniedException(
          identity.actorId(), caseDefinitionId, decision.reason());
    }

    var dedupedActions =
        actions.stream()
            .map(WorkerAction::aclAction)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    Instant maxExpiry = Instant.now().plus(MAX_CREDENTIAL_TTL);
    Instant expiry = deadline != null && deadline.isBefore(maxExpiry) ? deadline : maxExpiry;

    var resourceId = new ResourceId(EngineResourceTypes.CASE, caseId.toString());
    List<AclEntryRequest> requests =
        dedupedActions.stream()
            .map(a -> new AclEntryRequest(identity.actorId(), resourceId, a, expiry))
            .toList();
    accessControlProvider.grantBatch(requests);

    String token = generateToken();
    var credential =
        new WorkerCredential(
            token,
            identity.actorId(),
            resourceId,
            tenancyId,
            Set.copyOf(actions),
            expiry,
            Instant.now());
    try {
      credentialStore.store(credential);
    } catch (RuntimeException e) {
      accessControlProvider.revokeBatch(requests);
      throw e;
    }

    LOG.infof(
        "Granted worker credential: actor=%s case=%s actions=%s ephemeral=%s expires=%s",
        identity.actorId(), caseId, actions, identity.ephemeral(), expiry);
    return credential;
  }

  public void revokeForWorker(String token, String actorId, UUID caseId, boolean ephemeral) {
    var resourceId = new ResourceId(EngineResourceTypes.CASE, caseId.toString());
    var revoked = credentialStore.lookup(token);
    credentialStore.revoke(token);

    if (revoked.isEmpty()) {
      LOG.warnf(
          "Credential not found for revocation: token=...%s",
          token.length() > 6 ? token.substring(token.length() - 6) : token);
      return;
    }

    var revokedActions =
        revoked.get().actions().stream()
            .map(WorkerAction::aclAction)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    if (!ephemeral) {
      var remaining = credentialStore.findActiveByActorAndResource(actorId, resourceId);
      var stillNeeded =
          remaining.stream()
              .flatMap(c -> c.actions().stream())
              .map(WorkerAction::aclAction)
              .collect(Collectors.toSet());
      revokedActions.removeAll(stillNeeded);
    }

    if (!revokedActions.isEmpty()) {
      List<AclEntryRequest> requests =
          revokedActions.stream()
              .map(a -> new AclEntryRequest(actorId, resourceId, a, null))
              .toList();
      accessControlProvider.revokeBatch(requests);
    }

    LOG.infof(
        "Revoked worker credential: actor=%s case=%s ephemeral=%s", actorId, caseId, ephemeral);
  }

  public void revokeForCase(UUID caseId) {
    var resourceId = new ResourceId(EngineResourceTypes.CASE, caseId.toString());
    var revoked = credentialStore.revokeByResource(resourceId);
    for (var credential : revoked) {
      accessControlProvider.revokeAll(credential.actorId(), resourceId);
    }
    if (!revoked.isEmpty()) {
      LOG.infof(
          "Case terminal sweep: revoked %d credential(s) for case=%s", revoked.size(), caseId);
    }
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    StringBuilder sb = new StringBuilder(64);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
