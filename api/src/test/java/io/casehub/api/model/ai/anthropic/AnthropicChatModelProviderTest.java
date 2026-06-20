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
package io.casehub.api.model.ai.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.ModelType;
import org.junit.jupiter.api.Test;

class AnthropicChatModelProviderTest {

  @Test
  void typeIsAnthropic() {
    AnthropicChatModelProvider provider =
        AnthropicChatModelProvider.builder().apiKey("test-key").build();
    assertEquals(ModelType.ANTHROPIC, provider.type());
  }

  @Test
  void builderWithMinimalFieldsBuildsModel() {
    AnthropicChatModelProvider provider =
        AnthropicChatModelProvider.builder().apiKey("test-key").build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderWithAllFieldsBuildsModel() {
    AnthropicChatModelProvider provider =
        AnthropicChatModelProvider.builder()
            .apiKey("test-key")
            .modelName("claude-3-5-sonnet-20241022")
            .baseUrl("https://custom-anthropic.example.com")
            .version("2023-06-01")
            .temperature(0.7)
            .topP(0.9)
            .topK(40)
            .maxTokens(1000)
            .build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderRequiresApiKey() {
    assertThrows(IllegalStateException.class, () -> AnthropicChatModelProvider.builder().build());
  }

  @Test
  void builderWithCustomModelNameBuildsModel() {
    AnthropicChatModelProvider provider =
        AnthropicChatModelProvider.builder()
            .apiKey("test-key")
            .modelName("claude-3-haiku-20240307")
            .build();
    assertNotNull(provider.get());
  }
}
