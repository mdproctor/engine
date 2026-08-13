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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.platform.api.acl.ResourceId;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseScopeExtractorTest {

  private final CaseScopeExtractor extractor = new CaseScopeExtractor();

  @Test
  void extractsResourceId_fromCasePath() {
    UUID caseId = UUID.randomUUID();
    var ctx = new StubRequestContext(null, "cases/" + caseId + "/context");

    Optional<ResourceId> result = extractor.extractResourceId(ctx);

    assertThat(result).isPresent();
    assertThat(result.get().type()).isEqualTo(EngineResourceTypes.CASE);
    assertThat(result.get().id()).isEqualTo(caseId.toString());
  }

  @Test
  void returnsEmpty_whenNoCaseIdInPath() {
    var ctx = new StubRequestContext(null, "health");

    Optional<ResourceId> result = extractor.extractResourceId(ctx);

    assertThat(result).isEmpty();
  }

  @Test
  void extractsResourceId_fromNestedCasePath() {
    UUID caseId = UUID.randomUUID();
    var ctx = new StubRequestContext(null, "api/v1/cases/" + caseId + "/events");

    Optional<ResourceId> result = extractor.extractResourceId(ctx);

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo(caseId.toString());
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
