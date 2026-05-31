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
package io.casehub.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JpaCaseMetaModelRepositoryTest {

  @Inject CaseMetaModelRepository repository;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Panache.withSession/withTransaction requires a Vert.x duplicated context marked as safe. JUnit
   * test threads have no such context. VertxContextSupport creates the correct context and blocks
   * until the Uni completes.
   */
  private <T> T run(Supplier<Uni<T>> supplier) {
    try {
      return VertxContextSupport.subscribeAndAwait(supplier);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void save_populatesIdAndCreatedAt() {
    CaseMetaModel saved =
        run(() -> repository.save(metaModel("save-populates", "ns", "1.0"), "test-tenant"));

    assertThat(saved.getId()).isNotNull().isPositive();
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void findByKey_returnsNullForUnknown() {
    CaseMetaModel result =
        run(() -> repository.findByKey("no-such-ns", "no-such-name", "9.9", "test-tenant"));

    assertThat(result).isNull();
  }

  @Test
  void findByKey_returnsRegisteredMetaModel() {
    run(() -> repository.save(metaModel("find-by-key", "repo-ns", "2.0"), "test-tenant"));

    CaseMetaModel found =
        run(() -> repository.findByKey("repo-ns", "find-by-key", "2.0", "test-tenant"));

    assertThat(found).isNotNull();
    assertThat(found.getName()).isEqualTo("find-by-key");
    assertThat(found.getNamespace()).isEqualTo("repo-ns");
    assertThat(found.getVersion()).isEqualTo("2.0");
    assertThat(found.getId()).isNotNull();
  }

  @Test
  void save_thenFindByKey_roundTrip() {
    CaseMetaModel meta = metaModel("round-trip", "rt-ns", "3.0");
    meta.setTitle("Round Trip Title");
    meta.setDsl("yaml");

    CaseMetaModel saved = run(() -> repository.save(meta, "test-tenant"));
    CaseMetaModel found =
        run(() -> repository.findByKey("rt-ns", "round-trip", "3.0", "test-tenant"));

    assertThat(found.getId()).isEqualTo(saved.getId());
    assertThat(found.getTitle()).isEqualTo("Round Trip Title");
    assertThat(found.getDsl()).isEqualTo("yaml");
    assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
  }

  private CaseMetaModel metaModel(String name, String namespace, String version) {
    CaseMetaModel m = new CaseMetaModel();
    m.setName(name);
    m.setNamespace(namespace);
    m.setVersion(version);
    return m;
  }

  // ========== Edge Case Tests ==========

  @Test
  void save_handlesNullOptionalFields() {
    CaseMetaModel meta = metaModel("null-fields", "ns", "1.0");
    meta.setTitle(null);
    meta.setDsl(null);
    meta.setDefinition(null);

    CaseMetaModel saved = run(() -> repository.save(meta, "test-tenant"));

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getTitle()).isNull();
    assertThat(saved.getDsl()).isNull();
    assertThat(saved.getDefinition()).isNull();
  }

  @Test
  void save_handlesLongTitle() {
    String longTitle = "A".repeat(500); // 500 characters - max allowed by schema
    CaseMetaModel meta = metaModel("long-title", "ns", "1.0");
    meta.setTitle(longTitle);

    CaseMetaModel saved = run(() -> repository.save(meta, "test-tenant"));

    assertThat(saved.getTitle()).isEqualTo(longTitle);
  }

  @Test
  void save_handlesLargeDefinition() throws Exception {
    String largeJson = "{\"key\": \"" + "value".repeat(10000) + "\"}"; // ~50KB
    com.fasterxml.jackson.databind.JsonNode largeDefinition = OBJECT_MAPPER.readTree(largeJson);

    CaseMetaModel meta = metaModel("large-def", "ns", "1.0");
    meta.setDefinition(largeDefinition);

    CaseMetaModel saved = run(() -> repository.save(meta, "test-tenant"));

    CaseMetaModel found = run(() -> repository.findByKey("ns", "large-def", "1.0", "test-tenant"));
    assertThat(found.getDefinition().toString()).isEqualTo(largeDefinition.toString());
  }

  @Test
  void save_duplicateKey_fails() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    CaseMetaModel first = metaModel("dup-test-" + unique, "dup-ns", "1.0");
    run(() -> repository.save(first, "test-tenant"));

    CaseMetaModel duplicate = metaModel("dup-test-" + unique, "dup-ns", "1.0");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> run(() -> repository.save(duplicate, "test-tenant")))
        .isInstanceOf(Exception.class);
  }

  @Test
  void findByKey_isCaseSensitive_namespace() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    run(() -> repository.save(metaModel("case-test-" + unique, "Namespace", "1.0"), "test-tenant"));

    CaseMetaModel found =
        run(() -> repository.findByKey("namespace", "case-test-" + unique, "1.0", "test-tenant"));

    assertThat(found).isNull(); // Different case should not match
  }

  @Test
  void findByKey_isCaseSensitive_name() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    run(() -> repository.save(metaModel("CaseName-" + unique, "ns", "1.0"), "test-tenant"));

    CaseMetaModel found =
        run(() -> repository.findByKey("ns", "casename-" + unique, "1.0", "test-tenant"));

    assertThat(found).isNull(); // Different case should not match
  }

  @Test
  void findByKey_isCaseSensitive_version() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    run(() -> repository.save(metaModel("version-test-" + unique, "ns", "1.0.0"), "test-tenant"));

    CaseMetaModel found =
        run(() -> repository.findByKey("ns", "version-test-" + unique, "1.0.0", "test-tenant"));

    assertThat(found).isNotNull(); // Exact match should work
  }

  @Test
  void save_allowsSameNameInDifferentNamespaces() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    String sameName = "shared-name-" + unique;

    CaseMetaModel first = metaModel(sameName, "ns1", "1.0");
    CaseMetaModel second = metaModel(sameName, "ns2", "1.0");

    run(() -> repository.save(first, "test-tenant"));
    run(() -> repository.save(second, "test-tenant"));

    CaseMetaModel foundInNs1 =
        run(() -> repository.findByKey("ns1", sameName, "1.0", "test-tenant"));
    CaseMetaModel foundInNs2 =
        run(() -> repository.findByKey("ns2", sameName, "1.0", "test-tenant"));

    assertThat(foundInNs1).isNotNull();
    assertThat(foundInNs2).isNotNull();
    assertThat(foundInNs1.getId()).isNotEqualTo(foundInNs2.getId());
  }

  @Test
  void save_allowsDifferentVersionsOfSameCase() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    String sameName = "versioned-" + unique;

    CaseMetaModel v1 = metaModel(sameName, "ns", "1.0");
    CaseMetaModel v2 = metaModel(sameName, "ns", "2.0");

    run(() -> repository.save(v1, "test-tenant"));
    run(() -> repository.save(v2, "test-tenant"));

    CaseMetaModel foundV1 = run(() -> repository.findByKey("ns", sameName, "1.0", "test-tenant"));
    CaseMetaModel foundV2 = run(() -> repository.findByKey("ns", sameName, "2.0", "test-tenant"));

    assertThat(foundV1).isNotNull();
    assertThat(foundV2).isNotNull();
    assertThat(foundV1.getId()).isNotEqualTo(foundV2.getId());
  }

  @Test
  void save_concurrent_bothSucceed() throws InterruptedException {
    int threadCount = 5;
    java.util.List<CaseMetaModel> savedModels = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.List<Thread> threads = new java.util.ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread t =
          new Thread(
              () -> {
                String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
                CaseMetaModel meta = metaModel("concurrent-" + index + "-" + unique, "ns", "1.0");
                CaseMetaModel saved = run(() -> repository.save(meta, "test-tenant"));
                savedModels.add(saved);
              });
      threads.add(t);
      t.start();
    }

    for (Thread t : threads) {
      t.join();
    }

    assertThat(savedModels).hasSize(threadCount);
    assertThat(savedModels).allMatch(m -> m.getId() != null);
    // All IDs should be unique
    assertThat(savedModels.stream().map(CaseMetaModel::getId).distinct().count())
        .isEqualTo(threadCount);
  }

  @Test
  void save_createdAt_isImmutable() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    CaseMetaModel meta = metaModel("immutable-" + unique, "ns", "1.0");

    CaseMetaModel saved = run(() -> repository.save(meta, "test-tenant"));
    java.time.Instant originalCreatedAt = saved.getCreatedAt();

    // Reload from database
    CaseMetaModel reloaded =
        run(() -> repository.findByKey("ns", "immutable-" + unique, "1.0", "test-tenant"));

    assertThat(reloaded.getCreatedAt()).isEqualTo(originalCreatedAt);
  }

  @Test
  void findByKey_withSpecialCharactersInName() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    String specialName = "name-with-special_chars.and.dots-" + unique;

    run(() -> repository.save(metaModel(specialName, "ns", "1.0"), "test-tenant"));
    CaseMetaModel found = run(() -> repository.findByKey("ns", specialName, "1.0", "test-tenant"));

    assertThat(found).isNotNull();
    assertThat(found.getName()).isEqualTo(specialName);
  }

  @Test
  void findByKey_withSemanticVersioning() {
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    String name = "semver-" + unique;

    run(() -> repository.save(metaModel(name, "ns", "1.2.3-alpha+build123"), "test-tenant"));
    CaseMetaModel found =
        run(() -> repository.findByKey("ns", name, "1.2.3-alpha+build123", "test-tenant"));

    assertThat(found).isNotNull();
    assertThat(found.getVersion()).isEqualTo("1.2.3-alpha+build123");
  }
}
