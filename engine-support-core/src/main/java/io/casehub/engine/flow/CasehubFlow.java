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
package io.casehub.engine.flow;

import io.quarkus.arc.Arc;
import io.serverlessworkflow.impl.WorkflowContextData;
import java.util.Map;

/**
 * Static utility for dispatching casehub capabilities from Java FuncDSL workflow steps.
 *
 * <p>{@link #dispatch} blocks on the result via {@code .join()} — safe because quarkus-flow runs
 * steps on {@code Executors.newCachedThreadPool()} (not Vert.x IO threads). The YAML path via
 * {@link CasehubCallableTaskBuilder} is fully async and does not block.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * list.function("analyzeDocument", b -> b.function(
 *     (JavaFilterFunction<Map, Map>)(input, wfCtx, taskCtx) ->
 *         CasehubFlow.dispatch(wfCtx, "analyze-document"),
 *     Map.class));
 * }</pre>
 */
public final class CasehubFlow {

  private CasehubFlow() {}

  /**
   * Dispatches a casehub capability and blocks until the result is available. Safe to call from
   * FuncDSL step lambdas — quarkus-flow uses a cached thread pool, not Vert.x IO threads.
   */
  public static Map<String, Object> dispatch(
      final WorkflowContextData ctx, final String capability) {
    return Arc.container()
        .instance(CasehubDispatch.class)
        .get()
        .dispatch(ctx.instanceData().id(), capability)
        .join();
  }
}
