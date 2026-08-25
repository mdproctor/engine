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
package io.casehub.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares two JSON Schema trees structurally — same properties, types, constraints, $ref targets.
 * Descriptions are compared only when {@code compareDescriptions} is true.
 */
public class SchemaComparator {

  public record Result(boolean isEquivalent, List<String> differences) {
    public String report() {
      return String.join("\n", differences);
    }
  }

  private final boolean compareDescriptions;
  private final Set<String> ignoredDefPrefixes;

  public SchemaComparator(boolean compareDescriptions) {
    this.compareDescriptions = compareDescriptions;
    this.ignoredDefPrefixes = Set.of();
  }

  public Result compare(JsonNode expected, JsonNode actual) {
    List<String> diffs = new ArrayList<>();
    JsonNode cleanExpected = expected.deepCopy();
    stripIgnoredProperties(cleanExpected);
    compareNodes("$", cleanExpected, actual, diffs);
    return new Result(diffs.isEmpty(), diffs);
  }

  private void stripIgnoredProperties(JsonNode node) {
    if (node.isObject()) {
      ObjectNode obj = (ObjectNode) node;
      List<String> toRemove = new ArrayList<>();
      obj.fieldNames()
          .forEachRemaining(
              name -> {
                for (String prefix : ignoredDefPrefixes) {
                  if (name.startsWith(prefix)) {
                    toRemove.add(name);
                  }
                }
              });
      toRemove.forEach(obj::remove);
      obj.fields().forEachRemaining(e -> stripIgnoredProperties(e.getValue()));
    } else if (node.isArray()) {
      node.forEach(this::stripIgnoredProperties);
    }
  }

  private void compareNodes(String path, JsonNode expected, JsonNode actual, List<String> diffs) {
    if (expected == null && actual == null) return;
    if (expected == null) {
      diffs.add(path + ": expected null, got " + actual.getNodeType());
      return;
    }
    if (actual == null || actual.isMissingNode()) {
      diffs.add(path + ": expected " + expected.getNodeType() + ", got null");
      return;
    }

    if (expected.isObject() && actual.isObject()) {
      compareObjects(path, (ObjectNode) expected, (ObjectNode) actual, diffs);
    } else if (expected.isArray() && actual.isArray()) {
      compareArrays(path, expected, actual, diffs);
    } else if (!expected.equals(actual)) {
      if (isDescriptionField(path) && !compareDescriptions) {
        return;
      }
      diffs.add(path + ": expected " + expected + ", got " + actual);
    }
  }

  private void compareObjects(
      String path, ObjectNode expected, ObjectNode actual, List<String> diffs) {
    Set<String> expectedFields = new HashSet<>();
    expected.fieldNames().forEachRemaining(expectedFields::add);

    Set<String> actualFields = new HashSet<>();
    actual.fieldNames().forEachRemaining(actualFields::add);

    for (String field : expectedFields) {
      if (isDescriptionField(path + "." + field) && !compareDescriptions) {
        continue;
      }
      if (!actualFields.contains(field)) {
        diffs.add(path + "." + field + ": missing in generated schema");
      } else {
        compareNodes(path + "." + field, expected.get(field), actual.get(field), diffs);
      }
    }

    for (String field : actualFields) {
      if (isDescriptionField(path + "." + field) && !compareDescriptions) {
        continue;
      }
      if (!expectedFields.contains(field)) {
        diffs.add(path + "." + field + ": unexpected in generated schema");
      }
    }
  }

  private void compareArrays(String path, JsonNode expected, JsonNode actual, List<String> diffs) {
    if (isRequiredArray(path)) {
      compareRequiredArrays(path, expected, actual, diffs);
    } else if (expected.size() != actual.size()) {
      diffs.add(
          path + ": array size mismatch — expected " + expected.size() + ", got " + actual.size());
    } else {
      for (int i = 0; i < expected.size(); i++) {
        compareNodes(path + "[" + i + "]", expected.get(i), actual.get(i), diffs);
      }
    }
  }

  private void compareRequiredArrays(
      String path, JsonNode expected, JsonNode actual, List<String> diffs) {
    Set<String> expectedSet = new HashSet<>();
    expected.forEach(n -> expectedSet.add(n.asText()));
    Set<String> actualSet = new HashSet<>();
    actual.forEach(n -> actualSet.add(n.asText()));
    if (!expectedSet.equals(actualSet)) {
      Set<String> missing = new HashSet<>(expectedSet);
      missing.removeAll(actualSet);
      Set<String> extra = new HashSet<>(actualSet);
      extra.removeAll(expectedSet);
      if (!missing.isEmpty()) diffs.add(path + ": missing required " + missing);
      if (!extra.isEmpty()) diffs.add(path + ": extra required " + extra);
    }
  }

  private static boolean isRequiredArray(String path) {
    return path.endsWith(".required");
  }

  private static boolean isDescriptionField(String path) {
    return path.endsWith(".description");
  }
}
