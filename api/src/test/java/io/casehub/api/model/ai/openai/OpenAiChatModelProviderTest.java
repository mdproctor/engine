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
package io.casehub.api.model.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.ModelType;
import org.junit.jupiter.api.Test;

class OpenAiChatModelProviderTest {

  @Test
  void typeIsOpenAi() {
    OpenAiChatModelProvider provider = OpenAiChatModelProvider.builder().apiKey("test-key").build();
    assertEquals(ModelType.OPENAI, provider.type());
  }

  @Test
  void builderWithMinimalFieldsBuildsModel() {
    OpenAiChatModelProvider provider = OpenAiChatModelProvider.builder().apiKey("test-key").build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderWithAllFieldsBuildsModel() {
    OpenAiChatModelProvider provider =
        OpenAiChatModelProvider.builder()
            .apiKey("test-key")
            .modelName("gpt-4o")
            .temperature(0.5)
            .topP(0.9)
            .maxTokens(500)
            .maxCompletionTokens(200)
            .build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderRequiresApiKey() {
    assertThrows(IllegalStateException.class, () -> OpenAiChatModelProvider.builder().build());
  }

  @Test
  void builderWithCustomModelNameBuildsModel() {
    OpenAiChatModelProvider provider =
        OpenAiChatModelProvider.builder().apiKey("test-key").modelName("gpt-4o").build();
    assertNotNull(provider.get());
  }
}
