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
package io.casehub.persistence.memory;

import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CaseInstanceRepositoryContractTest;

class InMemoryCaseInstanceRepositoryContractTest extends CaseInstanceRepositoryContractTest {

  private final InMemoryCaseInstanceRepository repo = new InMemoryCaseInstanceRepository();

  @Override
  protected CaseInstanceRepository repository() {
    return repo;
  }

  @Override
  protected String tenancyId() {
    return "test-tenant";
  }
}
