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
package io.casehub.actorstate;

import io.casehub.work.api.spi.WorkloadProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test-only zero-returning WorkloadProvider stub.
 *
 * <p>Required by any @QuarkusTest that includes casehub-engine: engine#378 removed
 * CasehubWorkloadProvider; JpaWorkloadProvider is excluded via arc.exclude-types to avoid DB schema
 * issues. Without this stub CDI startup fails with an unsatisfied dependency on WorkloadProvider.
 *
 * <p>See protocol: casehub/workload-provider-stub-required-in-tests.md
 */
@ApplicationScoped
@DefaultBean
public class StubWorkloadProvider implements WorkloadProvider {

  @Override
  public int getActiveWorkCount(final String agentId) {
    return 0;
  }
}
