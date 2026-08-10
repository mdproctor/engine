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
package io.casehub.engine.planning.adaptation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ForwardReplanRevisionTest {

  @Test
  void idIsForwardReplan() {
    assertEquals("forward-replan", new ForwardReplanRevision().id());
  }

  @Test
  void includesConstraintTextInRevisionPrompt() {
    var capturedPrompt = new java.util.concurrent.atomic.AtomicReference<String>();

    dev.langchain4j.model.chat.ChatModel capturingModel =
        new dev.langchain4j.model.chat.ChatModel() {
          @Override
          public dev.langchain4j.model.chat.response.ChatResponse doChat(
              dev.langchain4j.model.chat.request.ChatRequest request) {
            for (var msg : request.messages()) {
              if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
                capturedPrompt.set(um.singleText());
              }
            }
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(
                    dev.langchain4j.data.message.AiMessage.from(
                        "{\"steps\": [{\"id\": \"s1\", \"description\": \"step\", \"capabilityName\": \"analysis\"}]}"))
                .build();
          }
        };

    io.casehub.api.model.ai.ChatModelProvider provider =
        new io.casehub.api.model.ai.ChatModelProvider() {
          @Override
          public io.casehub.api.model.ai.ModelType type() {
            return io.casehub.api.model.ai.ModelType.ANTHROPIC;
          }

          @Override
          public dev.langchain4j.model.chat.ChatModel get() {
            return capturingModel;
          }
        };

    var revision = new ForwardReplanRevision();
    try {
      var field = ForwardReplanRevision.class.getDeclaredField("chatModelProviders");
      field.setAccessible(true);
      field.set(revision, satisfiedInstance(provider));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    var constraints =
        new io.casehub.engine.plan.PlanningConstraints(
            java.time.Duration.ofMinutes(30),
            3,
            java.util.Map.of("speed", 0.8),
            java.util.Map.of("tokens", 5000));
    var definition =
        io.casehub.api.model.CaseDefinition.builder()
            .namespace("io.test")
            .name("test")
            .version("1.0")
            .build();
    definition.setPlanningConstraints(constraints);

    var adaptCtx =
        new io.casehub.engine.plan.adaptation.AdaptationContext(
            java.util.UUID.randomUUID(),
            "tenant-1",
            "compound-1",
            "analyse",
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
            definition,
            io.casehub.api.model.TaskStatus.COMPLETED,
            "binding-1",
            0);
    var cause =
        new io.casehub.engine.plan.adaptation.AdaptationCause.StepCompleted(
            "step-1", "analysis", java.util.Map.of());
    var context =
        new io.casehub.engine.plan.adaptation.RevisionContext(
            adaptCtx,
            cause,
            java.util.List.of(new io.casehub.worker.api.Capability("analysis", "", "", null)),
            java.util.List.of());

    revision.revise(context).await().indefinitely();

    org.assertj.core.api.Assertions.assertThat(capturedPrompt.get())
        .contains("30 minutes")
        .contains("3")
        .contains("5000")
        .contains("speed");
  }

  @SuppressWarnings("unchecked")
  private static jakarta.enterprise.inject.Instance<io.casehub.api.model.ai.ChatModelProvider>
      satisfiedInstance(io.casehub.api.model.ai.ChatModelProvider provider) {
    return new jakarta.enterprise.inject.Instance<>() {
      @Override
      public io.casehub.api.model.ai.ChatModelProvider get() {
        return provider;
      }

      @Override
      public boolean isUnsatisfied() {
        return false;
      }

      @Override
      public boolean isResolvable() {
        return true;
      }

      @Override
      public boolean isAmbiguous() {
        return false;
      }

      @Override
      public jakarta.enterprise.inject.Instance<io.casehub.api.model.ai.ChatModelProvider> select(
          java.lang.annotation.Annotation... qualifiers) {
        return this;
      }

      @Override
      public <U extends io.casehub.api.model.ai.ChatModelProvider>
          jakarta.enterprise.inject.Instance<U> select(
              Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <U extends io.casehub.api.model.ai.ChatModelProvider>
          jakarta.enterprise.inject.Instance<U> select(
              jakarta.enterprise.util.TypeLiteral<U> subtype,
              java.lang.annotation.Annotation... qualifiers) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void destroy(io.casehub.api.model.ai.ChatModelProvider instance) {}

      @Override
      public jakarta.enterprise.inject.Instance.Handle<io.casehub.api.model.ai.ChatModelProvider>
          getHandle() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Iterable<
              ? extends
                  jakarta.enterprise.inject.Instance.Handle<
                      io.casehub.api.model.ai.ChatModelProvider>>
          handles() {
        throw new UnsupportedOperationException();
      }

      @Override
      public java.util.Iterator<io.casehub.api.model.ai.ChatModelProvider> iterator() {
        return java.util.List.of(provider).iterator();
      }
    };
  }
}
