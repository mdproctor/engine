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
package io.casehub.engine.rest.filter;

import io.casehub.engine.common.spi.acl.WorkerCredentialStore;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class WorkerCredentialFilter implements ContainerRequestFilter {

  private static final Logger LOG = Logger.getLogger(WorkerCredentialFilter.class);
  private static final String HEADER = "X-Worker-Credential";
  private static final Pattern CASE_ID_PATTERN = Pattern.compile("cases/([0-9a-f-]{36})");

  private final WorkerCredentialStore credentialStore;

  @Inject
  public WorkerCredentialFilter(WorkerCredentialStore credentialStore) {
    this.credentialStore = credentialStore;
  }

  @Override
  public void filter(ContainerRequestContext ctx) {
    String token = ctx.getHeaderString(HEADER);
    if (token == null) {
      return;
    }

    var credential = credentialStore.lookup(token);
    if (credential.isEmpty()) {
      ctx.abortWith(Response.status(401).entity("Invalid worker credential").build());
      return;
    }

    var cred = credential.get();
    if (cred.isExpired()) {
      ctx.abortWith(Response.status(401).entity("Worker credential expired").build());
      return;
    }

    String path = ctx.getUriInfo().getPath();
    Matcher matcher = CASE_ID_PATTERN.matcher(path);
    if (matcher.find()) {
      UUID requestCaseId;
      try {
        requestCaseId = UUID.fromString(matcher.group(1));
      } catch (IllegalArgumentException e) {
        ctx.abortWith(Response.status(400).entity("Invalid case ID in path").build());
        return;
      }
      if (!cred.caseId().equals(requestCaseId)) {
        LOG.warnf(
            "Worker credential scope violation: token case=%s request case=%s",
            cred.caseId(), requestCaseId);
        ctx.abortWith(Response.status(403).entity("Credential not scoped for this case").build());
        return;
      }
    }

    ctx.setProperty("workerCredential.actorId", cred.actorId());
    ctx.setProperty("workerCredential.caseId", cred.caseId());
  }
}
