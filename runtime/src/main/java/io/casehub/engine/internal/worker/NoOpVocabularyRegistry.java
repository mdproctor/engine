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
package io.casehub.engine.internal.worker;

import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.MatchDegree;
import io.casehub.eidos.api.VocabularyMetadata;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.api.VocabularyTerm;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * No-op vocabulary registry providing exact-match-only semantics. When {@code
 * casehub-eidos-runtime} is on the classpath, {@code CdiVocabularyRegistry} displaces this
 * automatically.
 */
@DefaultBean
@ApplicationScoped
public class NoOpVocabularyRegistry implements VocabularyRegistry {

  @Override
  public <T extends Enum<T> & VocabularyTerm> void register(final Class<T> vocab) {}

  @Override
  public boolean isRegistered(final String vocabUri) {
    return false;
  }

  @Override
  public Optional<? extends VocabularyTerm> resolve(final String vocabUri, final String value) {
    return Optional.empty();
  }

  @Override
  public List<? extends VocabularyTerm> allTerms(final String vocabUri) {
    return List.of();
  }

  @Override
  public Optional<String> equivalentValues(
      final String fromUri, final String value, final String toUri) {
    return Optional.empty();
  }

  @Override
  public Optional<String> equivalentValues(
      final String fromUri, final String value, final String toUri, final DispositionAxis axis) {
    return Optional.empty();
  }

  @Override
  public <T extends Enum<T> & VocabularyTerm> Optional<T> resolve(
      final Class<T> vocab, final String value) {
    return Optional.empty();
  }

  @Override
  public <S extends Enum<S> & VocabularyTerm, T extends Enum<T> & VocabularyTerm>
      Optional<T> equivalentValues(final S from, final Class<T> targetVocab) {
    return Optional.empty();
  }

  @Override
  public <S extends Enum<S> & VocabularyTerm, T extends Enum<T> & VocabularyTerm>
      Optional<T> equivalentValues(
          final S from, final Class<T> targetVocab, final DispositionAxis axis) {
    return Optional.empty();
  }

  @Override
  public Optional<VocabularyMetadata> vocabularyMetadata(final String uri) {
    return Optional.empty();
  }

  @Override
  public boolean subsumes(
      final String vocabUri, final String generalValue, final String specificValue) {
    return generalValue != null && generalValue.equals(specificValue);
  }

  @Override
  public MatchDegree match(
      final String vocabUri, final String declaredValue, final String requestedValue) {
    if (declaredValue != null && declaredValue.equals(requestedValue)) {
      return new MatchDegree.Exact();
    }
    return new MatchDegree.None();
  }

  @Override
  public List<? extends VocabularyTerm> ancestors(final String vocabUri, final String value) {
    return List.of();
  }

  @Override
  public List<? extends VocabularyTerm> descendants(final String vocabUri, final String value) {
    return List.of();
  }

  @Override
  public Map<String, Set<String>> expandForMatchingByVocabulary(final String value) {
    return Map.of();
  }
}
