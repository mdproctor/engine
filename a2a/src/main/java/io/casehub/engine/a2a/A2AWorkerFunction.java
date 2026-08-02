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
package io.casehub.engine.a2a;

import io.casehub.worker.api.WorkerFunction;
import java.util.Map;

@SuppressWarnings("unchecked")
public record A2AWorkerFunction(
    String endpoint, String skill, boolean streaming, A2AAuthConfig auth)
    implements WorkerFunction<Map<String, Object>, Map<String, Object>> {

  @Override
  public Class<Map<String, Object>> inputType() {
    return (Class) Map.class;
  }

  @Override
  public Class<Map<String, Object>> outputType() {
    return (Class) Map.class;
  }
}
