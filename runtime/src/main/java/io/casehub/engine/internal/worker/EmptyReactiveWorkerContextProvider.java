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
package io.casehub.engine.internal.worker;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.api.spi.ReactiveWorkerContextProvider;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reactive default WorkerContextProvider. Returns a minimal context with the task capability as the
 * description, prior-worker history omitted, and open channels populated from {@link
 * ReactiveCaseChannelProvider#listChannels(UUID)}. Active by default — replace with a real reactive
 * implementation when richer context is needed.
 */
@DefaultBean
@ApplicationScoped
public class EmptyReactiveWorkerContextProvider implements ReactiveWorkerContextProvider {

  @Inject
  ReactiveCaseChannelProvider reactiveCaseChannelProvider; // package-private for test injection

  @Override
  public Uni<WorkerContext> buildContext(String workerId, UUID caseId, WorkRequest task) {
    Uni<List<CaseChannel>> channelsUni =
        caseId != null
            ? reactiveCaseChannelProvider.listChannels(caseId)
            : Uni.createFrom().item(List.of());
    return channelsUni.map(
        channels ->
            new WorkerContext(
                task.capability(),
                caseId,
                channels,
                List.of(),
                PropagationContext.createRoot(),
                Map.of()));
  }
}
