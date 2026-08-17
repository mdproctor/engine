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

import io.casehub.engine.graphql.dto.CaseContextChangeEventType;
import io.casehub.engine.graphql.dto.CaseLifecycleEventType;
import io.smallrye.graphql.api.Subscription;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;

@GraphQLApi
@ApplicationScoped
public class CaseSubscriptionResolver {

  private final CaseEventPublisher publisher;

  @Inject
  public CaseSubscriptionResolver(CaseEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Subscription
  @Description("Live case lifecycle events — state transitions, goal satisfaction, cancellation")
  public Multi<CaseLifecycleEventType> caseLifecycle(@Name("caseId") UUID caseId) {
    return publisher
        .lifecycleStream()
        .filter(event -> event.caseId().equals(caseId))
        .map(CaseLifecycleEventType::from);
  }

  @Subscription
  @Description("Live case context changes — working layer, computed layer mutations")
  public Multi<CaseContextChangeEventType> caseContextChange(@Name("caseId") UUID caseId) {
    return publisher
        .contextChangeStream()
        .filter(event -> event.instance().getUuid().equals(caseId))
        .map(CaseContextChangeEventType::from);
  }
}
