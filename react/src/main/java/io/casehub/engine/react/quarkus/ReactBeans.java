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
package io.casehub.engine.react.quarkus;

import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.engine.react.ReActCycleEventHandler;
import io.casehub.engine.react.ReActWorkerFunctionHandler;
import io.casehub.engine.react.ReActWorkerFunctionProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.concurrent.ExecutorService;

@ApplicationScoped
public class ReactBeans {

  @Produces
  @ApplicationScoped
  ReActCycleEventHandler reActCycleEventHandler(EventLogRepository eventLogRepository) {
    return new ReActCycleEventHandler(eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  ReActWorkerFunctionProvider reActWorkerFunctionProvider() {
    return new ReActWorkerFunctionProvider();
  }

  @Produces
  @ApplicationScoped
  ReActWorkerFunctionHandler reActWorkerFunctionHandler(
      WorkerRuntimeFactory runtimeFactory,
      EventDispatcher eventDispatcher,
      @io.quarkus.virtual.threads.VirtualThreads ExecutorService executor) {
    return new ReActWorkerFunctionHandler(runtimeFactory, eventDispatcher, executor);
  }
}
