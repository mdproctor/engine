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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.casehub.platform.acl.inmem.InMemoryWorkerCredentialStore;
import io.casehub.platform.api.acl.WorkerAction;
import io.casehub.platform.api.acl.WorkerCredential;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerCredentialFilterTest {

  private InMemoryWorkerCredentialStore credentialStore;
  private WorkerCredentialFilter filter;

  @BeforeEach
  void setUp() {
    credentialStore = new InMemoryWorkerCredentialStore();
    filter = new WorkerCredentialFilter(credentialStore);
  }

  @Test
  void noHeader_passesThrough() {
    var ctx = new StubRequestContext(null, "cases/abc/context");
    filter.filter(ctx);
    assertNull(ctx.abortedResponse);
  }

  @Test
  void validToken_matchingCase_setsProperty() {
    UUID caseId = UUID.randomUUID();
    credentialStore.store(credential("tok1", "agent:w1", caseId));

    var ctx = new StubRequestContext("tok1", "cases/" + caseId + "/context");
    filter.filter(ctx);

    assertNull(ctx.abortedResponse);
    assertEquals("agent:w1", ctx.properties.get("workerCredential.actorId"));
    assertEquals(caseId, ctx.properties.get("workerCredential.caseId"));
  }

  @Test
  void unknownToken_returns401() {
    var ctx = new StubRequestContext("bad-token", "cases/abc/context");
    filter.filter(ctx);

    assertNotNull(ctx.abortedResponse);
    assertEquals(401, ctx.abortedResponse.getStatus());
  }

  @Test
  void expiredToken_returns401() {
    UUID caseId = UUID.randomUUID();
    credentialStore.store(
        new WorkerCredential(
            "tok1",
            "agent:w1",
            caseId,
            "tenant-1",
            Set.of(WorkerAction.READ_CONTEXT),
            Instant.now().minusSeconds(10),
            Instant.now().minusSeconds(3600)));

    var ctx = new StubRequestContext("tok1", "cases/" + caseId + "/context");
    filter.filter(ctx);

    assertNotNull(ctx.abortedResponse);
    assertEquals(401, ctx.abortedResponse.getStatus());
  }

  @Test
  void tokenForDifferentCase_returns403() {
    UUID caseA = UUID.randomUUID();
    UUID caseB = UUID.randomUUID();
    credentialStore.store(credential("tok1", "agent:w1", caseA));

    var ctx = new StubRequestContext("tok1", "cases/" + caseB + "/context");
    filter.filter(ctx);

    assertNotNull(ctx.abortedResponse);
    assertEquals(403, ctx.abortedResponse.getStatus());
  }

  @Test
  void validToken_noCaseIdInPath_passesThrough() {
    UUID caseId = UUID.randomUUID();
    credentialStore.store(credential("tok1", "agent:w1", caseId));

    var ctx = new StubRequestContext("tok1", "health");
    filter.filter(ctx);
    assertNull(ctx.abortedResponse);
  }

  private WorkerCredential credential(String token, String actorId, UUID caseId) {
    return new WorkerCredential(
        token,
        actorId,
        caseId,
        "tenant-1",
        Set.of(WorkerAction.READ_CONTEXT),
        Instant.now().plusSeconds(3600),
        Instant.now());
  }

  static class StubRequestContext implements ContainerRequestContext {
    final String token;
    final String path;
    Response abortedResponse;
    final java.util.Map<String, Object> properties = new java.util.HashMap<>();

    StubRequestContext(String token, String path) {
      this.token = token;
      this.path = path;
    }

    @Override
    public String getHeaderString(String name) {
      return "X-Worker-Credential".equals(name) ? token : null;
    }

    @Override
    public UriInfo getUriInfo() {
      return new StubUriInfo(path);
    }

    @Override
    public void abortWith(Response response) {
      this.abortedResponse = response;
    }

    @Override
    public void setProperty(String name, Object object) {
      properties.put(name, object);
    }

    @Override
    public Object getProperty(String name) {
      return properties.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
      return properties.keySet();
    }

    @Override
    public void removeProperty(String name) {
      properties.remove(name);
    }

    @Override
    public void setRequestUri(java.net.URI requestUri) {}

    @Override
    public void setRequestUri(java.net.URI baseUri, java.net.URI requestUri) {}

    @Override
    public jakarta.ws.rs.core.Request getRequest() {
      return null;
    }

    @Override
    public String getMethod() {
      return "GET";
    }

    @Override
    public void setMethod(String method) {}

    @Override
    public jakarta.ws.rs.core.MultivaluedMap<String, String> getHeaders() {
      return null;
    }

    @Override
    public java.util.Date getDate() {
      return null;
    }

    @Override
    public java.util.Locale getLanguage() {
      return null;
    }

    @Override
    public int getLength() {
      return 0;
    }

    @Override
    public jakarta.ws.rs.core.MediaType getMediaType() {
      return null;
    }

    @Override
    public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
      return List.of();
    }

    @Override
    public List<java.util.Locale> getAcceptableLanguages() {
      return List.of();
    }

    @Override
    public Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
      return Map.of();
    }

    @Override
    public boolean hasEntity() {
      return false;
    }

    @Override
    public java.io.InputStream getEntityStream() {
      return null;
    }

    @Override
    public void setEntityStream(java.io.InputStream input) {}

    @Override
    public jakarta.ws.rs.core.SecurityContext getSecurityContext() {
      return null;
    }

    @Override
    public void setSecurityContext(jakarta.ws.rs.core.SecurityContext context) {}
  }

  static class StubUriInfo implements UriInfo {
    final String path;

    StubUriInfo(String path) {
      this.path = path;
    }

    @Override
    public String getPath() {
      return path;
    }

    @Override
    public String getPath(boolean decode) {
      return path;
    }

    @Override
    public List<jakarta.ws.rs.core.PathSegment> getPathSegments() {
      return List.of();
    }

    @Override
    public List<jakarta.ws.rs.core.PathSegment> getPathSegments(boolean decode) {
      return List.of();
    }

    @Override
    public java.net.URI getRequestUri() {
      return null;
    }

    @Override
    public jakarta.ws.rs.core.UriBuilder getRequestUriBuilder() {
      return null;
    }

    @Override
    public java.net.URI getAbsolutePath() {
      return null;
    }

    @Override
    public jakarta.ws.rs.core.UriBuilder getAbsolutePathBuilder() {
      return null;
    }

    @Override
    public java.net.URI getBaseUri() {
      return null;
    }

    @Override
    public jakarta.ws.rs.core.UriBuilder getBaseUriBuilder() {
      return null;
    }

    @Override
    public jakarta.ws.rs.core.MultivaluedMap<String, String> getPathParameters() {
      return null;
    }

    @Override
    public jakarta.ws.rs.core.MultivaluedMap<String, String> getPathParameters(boolean decode) {
      return null;
    }

    @Override
    public jakarta.ws.rs.core.MultivaluedMap<String, String> getQueryParameters() {
      return null;
    }

    @Override
    public jakarta.ws.rs.core.MultivaluedMap<String, String> getQueryParameters(boolean decode) {
      return null;
    }

    @Override
    public List<String> getMatchedURIs() {
      return List.of();
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
      return List.of();
    }

    @Override
    public List<Object> getMatchedResources() {
      return List.of();
    }

    @Override
    public java.net.URI resolve(java.net.URI uri) {
      return null;
    }

    @Override
    public java.net.URI relativize(java.net.URI uri) {
      return null;
    }
  }
}
