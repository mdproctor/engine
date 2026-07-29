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
package io.casehub.engine.rest.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class CatchAllExceptionMapperTest {

  private final CatchAllExceptionMapper mapper = new CatchAllExceptionMapper();

  @Test
  void webApplicationException_preservesStatusCode() {
    Response response = mapper.toResponse(new BadRequestException("bad input"));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void notFoundException_preserves404() {
    Response response = mapper.toResponse(new NotFoundException("missing"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void genericRuntimeException_returns500() {
    Response response = mapper.toResponse(new IllegalStateException("unexpected"));
    assertThat(response.getStatus()).isEqualTo(500);
  }
}
