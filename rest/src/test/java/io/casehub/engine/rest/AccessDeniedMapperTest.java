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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.rest.dto.ProblemDetail;
import io.casehub.engine.rest.exception.AccessDeniedExceptionMapper;
import io.casehub.platform.api.acl.AccessDeniedException;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class AccessDeniedMapperTest {

  @Test
  void mapper_returns403_withGenericBody() {
    var mapper = new AccessDeniedExceptionMapper();
    var ex = new AccessDeniedException("alice", new ResourceId("case", "abc-123"), AclAction.READ);
    Response response = mapper.toResponse(ex);

    assertThat(response.getStatus()).isEqualTo(403);
    var body = (ProblemDetail) response.getEntity();
    assertThat(body.detail()).isEqualTo("Insufficient permissions");
    assertThat(body.detail()).doesNotContain("alice");
    assertThat(body.detail()).doesNotContain("abc-123");
  }

  @Test
  void mapper_returns403_forAdminAction() {
    var mapper = new AccessDeniedExceptionMapper();
    var ex = new AccessDeniedException("bob", new ResourceId("case", "xyz-789"), AclAction.ADMIN);
    Response response = mapper.toResponse(ex);

    assertThat(response.getStatus()).isEqualTo(403);
    var body = (ProblemDetail) response.getEntity();
    assertThat(body.detail()).isEqualTo("Insufficient permissions");
  }
}
