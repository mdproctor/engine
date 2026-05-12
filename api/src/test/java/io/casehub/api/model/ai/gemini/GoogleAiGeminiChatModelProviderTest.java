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
package io.casehub.api.model.ai.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.api.model.ai.ModelType;
import org.junit.jupiter.api.Test;

class GoogleAiGeminiChatModelProviderTest {

  @Test
  void typeIsGoogleAiGemini() {
    GoogleAiGeminiChatModelProvider provider =
        GoogleAiGeminiChatModelProvider.builder().apiKey("test-key").build();
    assertEquals(ModelType.GOOGLE_AI_GEMINI, provider.type());
  }

  @Test
  void builderWithMinimalFieldsBuildsModel() {
    GoogleAiGeminiChatModelProvider provider =
        GoogleAiGeminiChatModelProvider.builder().apiKey("test-key").build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderWithAllFieldsBuildsModel() {
    GoogleAiGeminiChatModelProvider provider =
        GoogleAiGeminiChatModelProvider.builder()
            .apiKey("test-key")
            .modelName("gemini-2.0-flash")
            .temperature(0.7)
            .topP(0.9)
            .maxOutputTokens(1000)
            .build();
    ChatModel model = provider.get();
    assertNotNull(model);
  }

  @Test
  void builderRequiresApiKey() {
    assertThrows(
        IllegalStateException.class, () -> GoogleAiGeminiChatModelProvider.builder().build());
  }

  @Test
  void builderWithCustomModelNameBuildsModel() {
    GoogleAiGeminiChatModelProvider provider =
        GoogleAiGeminiChatModelProvider.builder()
            .apiKey("test-key")
            .modelName("gemini-1.5-pro")
            .build();
    assertNotNull(provider.get());
  }
}
