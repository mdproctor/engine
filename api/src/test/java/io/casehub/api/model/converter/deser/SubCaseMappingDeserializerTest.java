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
package io.casehub.api.model.converter.deser;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.SubCaseMapping;
import io.casehub.api.model.converter.CaseDefinitionModule;
import org.junit.jupiter.api.Test;

class SubCaseMappingDeserializerTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new CaseDefinitionModule(null));

  @Test
  void string_deserializesToJqExpression() throws Exception {
    SubCaseMapping result = mapper.readValue("\".child\"", SubCaseMapping.class);
    assertInstanceOf(SubCaseMapping.Expression.class, result);
  }

  @Test
  void singleKeyMap_deserializesToExpression() throws Exception {
    SubCaseMapping result = mapper.readValue("{\"jq\": \".child\"}", SubCaseMapping.class);
    assertInstanceOf(SubCaseMapping.Expression.class, result);
  }

  @Test
  void nullValue_deserializesToNull() throws Exception {
    SubCaseMapping result = mapper.readValue("null", SubCaseMapping.class);
    assertNull(result);
  }
}
