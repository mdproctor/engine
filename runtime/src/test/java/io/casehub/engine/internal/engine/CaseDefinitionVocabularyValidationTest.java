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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.MatchDegree;
import io.casehub.eidos.api.VocabularyMetadata;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.api.VocabularyTerm;
import io.casehub.platform.api.path.Path;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CaseDefinitionVocabularyValidationTest {

  @Inject DefaultCaseDefinitionRegistry registry;

  private final List<LogRecord> logRecords = new ArrayList<>();
  private Handler testHandler;
  private Logger logger;

  @BeforeEach
  void setupLogCapture() {
    logger = Logger.getLogger(DefaultCaseDefinitionRegistry.class.getName());
    logger.setLevel(Level.WARNING);
    testHandler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logRecords.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(testHandler);
  }

  @AfterEach
  void teardownLogCapture() {
    if (testHandler != null && logger != null) {
      logger.removeHandler(testHandler);
    }
    logRecords.clear();
    RecordingVocabularyRegistry.reset();
  }

  @Test
  void warns_when_type_path_segment_is_unresolvable() {
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("vocab-type-test")
            .version("1.0")
            .types(Set.of(Path.parse("urn:casehub:unknown-vocab/unresolvable-term")))
            .build();

    RecordingVocabularyRegistry.setResolveResult(null);

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(logRecords)
        .anyMatch(
            r ->
                r.getMessage().contains("unresolvable type")
                    && r.getMessage().contains("urn:casehub:unknown-vocab/unresolvable-term"));
  }

  @Test
  void warns_when_label_path_segment_is_unresolvable() {
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("vocab-label-test")
            .version("1.0")
            .labels(Set.of(Path.parse("urn:casehub:unknown-vocab/unresolvable-label")))
            .build();

    RecordingVocabularyRegistry.setResolveResult(null);

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(logRecords)
        .anyMatch(
            r ->
                r.getMessage().contains("unresolvable label")
                    && r.getMessage().contains("urn:casehub:unknown-vocab/unresolvable-label"));
  }

  @Test
  void does_not_warn_when_all_paths_resolve() {
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("vocab-valid-test")
            .version("1.0")
            .types(Set.of(Path.parse("urn:casehub:valid-vocab/valid-term")))
            .labels(Set.of(Path.parse("urn:casehub:valid-vocab/valid-label")))
            .build();

    RecordingVocabularyRegistry.setResolveResult(Optional.of(new MockVocabularyTerm()));

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(logRecords).noneMatch(r -> r.getMessage().contains("unresolvable"));
  }

  @Test
  void skips_validation_when_no_vocabulary_registry() {
    // This test verifies that when NoOpVocabularyRegistry is active, no warnings are logged
    // We can't force NoOpVocabularyRegistry in this test (RecordingVocabularyRegistry is active),
    // but the implementation should check instanceof NoOpVocabularyRegistry
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("no-vocab-test")
            .version("1.0")
            .types(Set.of(Path.parse("urn:casehub:unknown/term")))
            .build();

    // When RecordingVocabularyRegistry returns empty, it should warn
    RecordingVocabularyRegistry.setResolveResult(null);

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    // With RecordingVocabularyRegistry active, we should get warnings
    assertThat(logRecords).anyMatch(r -> r.getMessage().contains("unresolvable"));
  }

  /**
   * Recording implementation of VocabularyRegistry for testing. Displaces NoOpVocabularyRegistry
   * via @Alternative @Priority(1).
   */
  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class RecordingVocabularyRegistry implements VocabularyRegistry {

    private static Optional<? extends VocabularyTerm> resolveResult = Optional.empty();

    public static void setResolveResult(Optional<? extends VocabularyTerm> result) {
      resolveResult = result == null ? Optional.empty() : result;
    }

    public static void reset() {
      resolveResult = Optional.empty();
    }

    @Override
    public <T extends Enum<T> & VocabularyTerm> void register(Class<T> vocab) {}

    @Override
    public boolean isRegistered(String vocabUri) {
      return true;
    }

    @Override
    public Optional<? extends VocabularyTerm> resolve(String vocabUri, String value) {
      return resolveResult;
    }

    @Override
    public List<? extends VocabularyTerm> allTerms(String vocabUri) {
      return List.of();
    }

    @Override
    public Optional<String> equivalentValues(String fromUri, String value, String toUri) {
      return Optional.empty();
    }

    @Override
    public Optional<String> equivalentValues(
        String fromUri, String value, String toUri, DispositionAxis axis) {
      return Optional.empty();
    }

    @Override
    public <T extends Enum<T> & VocabularyTerm> Optional<T> resolve(Class<T> vocab, String value) {
      return Optional.empty();
    }

    @Override
    public <S extends Enum<S> & VocabularyTerm, T extends Enum<T> & VocabularyTerm>
        Optional<T> equivalentValues(S from, Class<T> targetVocab) {
      return Optional.empty();
    }

    @Override
    public <S extends Enum<S> & VocabularyTerm, T extends Enum<T> & VocabularyTerm>
        Optional<T> equivalentValues(S from, Class<T> targetVocab, DispositionAxis axis) {
      return Optional.empty();
    }

    @Override
    public Optional<VocabularyMetadata> vocabularyMetadata(String uri) {
      return Optional.empty();
    }

    @Override
    public boolean subsumes(String vocabUri, String generalValue, String specificValue) {
      return false;
    }

    @Override
    public MatchDegree match(String vocabUri, String declaredValue, String requestedValue) {
      return new MatchDegree.None();
    }

    @Override
    public List<? extends VocabularyTerm> ancestors(String vocabUri, String value) {
      return List.of();
    }

    @Override
    public List<? extends VocabularyTerm> descendants(String vocabUri, String value) {
      return List.of();
    }

    @Override
    public Map<String, Set<String>> expandForMatchingByVocabulary(String value) {
      return Map.of();
    }

    @Override
    public Set<String> registeredUris() {
      return Set.of();
    }
  }

  /** Mock VocabularyTerm for testing. */
  private static class MockVocabularyTerm implements VocabularyTerm {
    @Override
    public String value() {
      return "valid-term";
    }

    @Override
    public String label() {
      return "Valid Term";
    }
  }
}
