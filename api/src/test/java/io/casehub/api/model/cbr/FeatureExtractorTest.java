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
package io.casehub.api.model.cbr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FeatureExtractorTest {

  @Test
  void jq_type_is_jq() {
    var jq = new JqFeatureExtractor(Map.of("f1", ".x"));
    assertEquals("jq", jq.type());
  }

  @Test
  void jq_rejects_null_expressions() {
    assertThrows(NullPointerException.class, () -> new JqFeatureExtractor(null));
  }

  @Test
  void jq_rejects_empty_expressions() {
    assertThrows(IllegalArgumentException.class, () -> new JqFeatureExtractor(Map.of()));
  }

  @Test
  void jq_defensively_copies_map() {
    var mutable = new java.util.HashMap<String, String>();
    mutable.put("f1", ".x");
    var jq = new JqFeatureExtractor(mutable);
    mutable.put("f2", ".y");
    assertEquals(1, jq.featureExpressions().size());
  }

  @Test
  void lambda_type_is_lambda() {
    var lambda = new LambdaFeatureExtractor(ctx -> Map.of());
    assertEquals("lambda", lambda.type());
  }

  @Test
  void lambda_rejects_null_function() {
    assertThrows(NullPointerException.class, () -> new LambdaFeatureExtractor(null));
  }

  @Test
  void sealed_permits_only_two_subtypes() {
    assertTrue(FeatureExtractor.class.isSealed());
    var permitted = FeatureExtractor.class.getPermittedSubclasses();
    assertEquals(2, permitted.length);
  }
}
