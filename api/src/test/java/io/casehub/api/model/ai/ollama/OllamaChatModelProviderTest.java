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
package io.casehub.api.model.ai.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.ModelType;
import org.junit.jupiter.api.Test;

class OllamaChatModelProviderTest {

  @Test
  void typeIsOllama() {
    OllamaChatModelProvider provider =
        OllamaChatModelProvider.builder().modelName("llama3").build();
    assertEquals(ModelType.OLLAMA, provider.type());
  }

  @Test
  void builderWithMinimalFieldsBuildsModel() {
    OllamaChatModelProvider provider =
        OllamaChatModelProvider.builder().modelName("llama3").build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderWithAllFieldsBuildsModel() {
    OllamaChatModelProvider provider =
        OllamaChatModelProvider.builder()
            .baseUrl("http://localhost:11434")
            .modelName("llama3")
            .temperature(0.7)
            .topP(0.9)
            .numPredict(500)
            .build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderRequiresModelName() {
    assertThrows(IllegalStateException.class, () -> OllamaChatModelProvider.builder().build());
  }

  @Test
  void builderWithDefaultBaseUrlBuildsModel() {
    OllamaChatModelProvider provider =
        OllamaChatModelProvider.builder().modelName("llama3").build();
    assertNotNull(provider.get());
  }
}
