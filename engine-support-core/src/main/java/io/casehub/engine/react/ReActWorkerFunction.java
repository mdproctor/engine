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
package io.casehub.engine.react;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.worker.api.WorkerFunction;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReActWorkerFunction(
    ChatModel model, String systemPrompt, List<ToolSource> tools, int maxCycles)
    implements WorkerFunction<Map, Map> {

  public ReActWorkerFunction {
    Objects.requireNonNull(tools);
    if (tools.isEmpty()) throw new IllegalArgumentException("ReAct requires at least one tool");
    if (maxCycles < 1) throw new IllegalArgumentException("maxCycles must be >= 1");
  }

  public ReActWorkerFunction(ChatModel model, String systemPrompt, List<ToolSource> tools) {
    this(model, systemPrompt, tools, 20);
  }

  @Override
  public Class<Map> inputType() {
    return Map.class;
  }

  @Override
  public Class<Map> outputType() {
    return Map.class;
  }
}
