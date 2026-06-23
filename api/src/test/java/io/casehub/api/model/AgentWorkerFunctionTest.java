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
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.ai.ModelType;
import io.casehub.worker.api.WorkerFunction;
import org.junit.jupiter.api.Test;

class AgentWorkerFunctionTest {

  @Test
  void implementsWorkerFunction() {
    Agent agent = testAgent();
    var fn = new AgentWorkerFunction(agent);
    assertInstanceOf(WorkerFunction.class, fn);
    assertSame(agent, fn.agent());
  }

  @Test
  void rejectsNullAgent() {
    assertThrows(NullPointerException.class, () -> new AgentWorkerFunction(null));
  }

  private Agent testAgent() {
    return Agent.builder()
        .systemPrompt("test")
        .model(
            new ChatModelProvider() {
              @Override
              public ModelType type() {
                return ModelType.OPENAI;
              }

              @Override
              public ChatModel get() {
                return new ChatModel() {
                  @Override
                  public ChatResponse doChat(ChatRequest request) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();
                  }
                };
              }
            })
        .build();
  }
}
