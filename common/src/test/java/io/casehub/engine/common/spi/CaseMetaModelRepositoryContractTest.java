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
package io.casehub.engine.common.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.query.CaseDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class CaseMetaModelRepositoryContractTest {

  protected abstract CaseMetaModelRepository repository();

  protected abstract String tenancyId();

  protected CaseMetaModel createMetaModel(String namespace, String name, String version) {
    CaseMetaModel m = new CaseMetaModel();
    m.setNamespace(namespace);
    m.setName(name);
    m.setVersion(version);
    return repository().save(m, tenancyId());
  }

  @BeforeEach
  void setUp() {
    // Subclasses may override to reset state
  }

  @Test
  void queryAll_returnsAllForTenant() {
    createMetaModel("ns", "case-a", "1.0");
    createMetaModel("ns", "case-b", "1.0");
    var result = repository().query(CaseDefinitionQuery.all(), tenancyId());
    assertThat(result).hasSize(2);
  }

  @Test
  void queryAll_excludesOtherTenants() {
    createMetaModel("ns", "case-a", "1.0");
    repository().save(createRaw("ns", "case-b", "1.0"), "other-tenant");
    var result = repository().query(CaseDefinitionQuery.all(), tenancyId());
    assertThat(result).hasSize(1);
  }

  @Test
  void queryByNamespace_filtersCorrectly() {
    createMetaModel("ns1", "case-a", "1.0");
    createMetaModel("ns2", "case-b", "1.0");
    var query = CaseDefinitionQuery.builder().namespace("ns1").build();
    assertThat(repository().query(query, tenancyId())).hasSize(1);
  }

  @Test
  void queryByName_filtersCorrectly() {
    createMetaModel("ns", "alpha", "1.0");
    createMetaModel("ns", "beta", "1.0");
    var query = CaseDefinitionQuery.builder().name("alpha").build();
    var result = repository().query(query, tenancyId());
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("alpha");
  }

  @Test
  void queryPagination_respectsPageAndSize() {
    for (int i = 0; i < 5; i++) {
      createMetaModel("ns", "case-" + i, "1.0");
    }
    var page0 = CaseDefinitionQuery.builder().size(2).page(0).build();
    var page1 = CaseDefinitionQuery.builder().size(2).page(1).build();
    var page2 = CaseDefinitionQuery.builder().size(2).page(2).build();
    assertThat(repository().query(page0, tenancyId())).hasSize(2);
    assertThat(repository().query(page1, tenancyId())).hasSize(2);
    assertThat(repository().query(page2, tenancyId())).hasSize(1);
  }

  @Test
  void count_returnsTotal() {
    createMetaModel("ns", "case-a", "1.0");
    createMetaModel("ns", "case-b", "1.0");
    assertThat(repository().count(CaseDefinitionQuery.all(), tenancyId())).isEqualTo(2);
  }

  @Test
  void count_respectsFilters() {
    createMetaModel("ns1", "case-a", "1.0");
    createMetaModel("ns2", "case-b", "1.0");
    var filtered = CaseDefinitionQuery.builder().namespace("ns1").build();
    assertThat(repository().count(filtered, tenancyId())).isEqualTo(1);
  }

  @Test
  void count_excludesOtherTenants() {
    createMetaModel("ns", "case-a", "1.0");
    repository().save(createRaw("ns", "case-b", "1.0"), "other-tenant");
    assertThat(repository().count(CaseDefinitionQuery.all(), tenancyId())).isEqualTo(1);
  }

  private CaseMetaModel createRaw(String namespace, String name, String version) {
    CaseMetaModel m = new CaseMetaModel();
    m.setNamespace(namespace);
    m.setName(name);
    m.setVersion(version);
    return m;
  }
}
