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
package io.casehub.engine.internal.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StateContextImpl.applyDiff")
class CaseContextImplApplyDiffTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonNode patch(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  // ------------------------------------------------------------------ //
  //  Helpers: roundtrip via snapshot().diff()                           //
  // ------------------------------------------------------------------ //

  /** Produces a patch that transforms {@code before} into {@code after}. */
  private JsonNode diffFromSnapshots(CaseContextImpl before, CaseContextImpl after) {
    return before.snapshot().diff(after);
  }

  // ================================================================== //

  @Nested
  @DisplayName("add operations")
  class AddOperations {

    @Test
    @DisplayName("should add a new string field to an empty context")
    void addStringToEmpty() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/status\",\"value\":\"active\"}]"));

      assertEquals("active", ctx.getString("status"));
    }

    @Test
    @DisplayName("should add a new integer field")
    void addInteger() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/amount\",\"value\":42}]"));

      assertEquals(42, ctx.getInt("amount"));
    }

    @Test
    @DisplayName("should add a boolean field")
    void addBoolean() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/approved\",\"value\":true}]"));

      assertTrue(ctx.getBoolean("approved"));
    }

    @Test
    @DisplayName("should add a null field")
    void addNull() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("x", "y"));
      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/empty\",\"value\":null}]"));

      assertTrue(ctx.contains("empty"));
      assertNull(ctx.get("empty"));
    }

    @Test
    @DisplayName("should add a nested object field")
    void addNestedObject() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      ctx.applyDiff(
          patch(
              """
                    [{"op":"add","path":"/payment","value":{"amount":500,"currency":"USD"}}]
                    """));

      @SuppressWarnings("unchecked")
      Map<String, Object> payment = (Map<String, Object>) ctx.get("payment");
      assertEquals(500, payment.get("amount"));
      assertEquals("USD", payment.get("currency"));
    }

    @Test
    @DisplayName("should add a list field")
    void addList() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/tags\",\"value\":[\"a\",\"b\"]}]"));

      List<?> tags = ctx.getList("tags", Object.class);
      assertEquals(2, tags.size());
      assertEquals("a", tags.get(0));
    }

    @Test
    @DisplayName("should add multiple fields in one patch")
    void addMultipleFields() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      ctx.applyDiff(
          patch(
              """
                    [
                      {"op":"add","path":"/status","value":"pending"},
                      {"op":"add","path":"/count","value":3}
                    ]
                    """));

      assertEquals("pending", ctx.getString("status"));
      assertEquals(3, ctx.getInt("count"));
    }
  }

  // ================================================================== //

  @Nested
  @DisplayName("replace operations")
  class ReplaceOperations {

    @Test
    @DisplayName("should replace an existing string field")
    void replaceString() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("status", "pending"));
      ctx.applyDiff(patch("[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"active\"}]"));

      assertEquals("active", ctx.getString("status"));
    }

    @Test
    @DisplayName("should replace an integer field")
    void replaceInteger() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("count", 1));
      ctx.applyDiff(patch("[{\"op\":\"replace\",\"path\":\"/count\",\"value\":99}]"));

      assertEquals(99, ctx.getInt("count"));
    }

    @Test
    @DisplayName("should replace a field with a nested object")
    void replaceWithObject() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("data", "simple"));
      ctx.applyDiff(patch("[{\"op\":\"replace\",\"path\":\"/data\",\"value\":{\"key\":\"val\"}}]"));

      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) ctx.get("data");
      assertEquals("val", data.get("key"));
    }

    @Test
    @DisplayName("should not affect other fields when replacing one")
    void replaceDoesNotAffectOthers() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("a", "1", "b", "2"));
      ctx.applyDiff(patch("[{\"op\":\"replace\",\"path\":\"/a\",\"value\":\"updated\"}]"));

      assertEquals("updated", ctx.getString("a"));
      assertEquals("2", ctx.getString("b"));
    }
  }

  // ================================================================== //

  @Nested
  @DisplayName("remove operations")
  class RemoveOperations {

    @Test
    @DisplayName("should remove an existing field")
    void removeField() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("status", "active", "other", "keep"));
      ctx.applyDiff(patch("[{\"op\":\"remove\",\"path\":\"/status\"}]"));

      assertFalse(ctx.contains("status"));
      assertTrue(ctx.contains("other"));
    }

    @Test
    @DisplayName("should leave context empty when removing the only field")
    void removeOnlyField() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("x", "y"));
      ctx.applyDiff(patch("[{\"op\":\"remove\",\"path\":\"/x\"}]"));

      assertTrue(ctx.isEmpty());
    }
  }

  // ================================================================== //

  @Nested
  @DisplayName("empty patch")
  class EmptyPatch {

    @Test
    @DisplayName("should leave context unchanged for empty patch array")
    void emptyPatchLeavesContextUnchanged() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("status", "original"));
      ctx.applyDiff(patch("[]"));

      assertEquals("original", ctx.getString("status"));
      assertEquals(1, ctx.size());
    }

    @Test
    @DisplayName("should still increment version for empty patch")
    void emptyPatchIncrementsVersion() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      long before = ctx.getVersion();
      ctx.applyDiff(patch("[]"));

      assertEquals(before + 1, ctx.getVersion());
    }
  }

  // ================================================================== //

  @Nested
  @DisplayName("version tracking")
  class VersionTracking {

    @Test
    @DisplayName("should increment version on each applyDiff call")
    void versionIncrementsEachCall() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      long initial = ctx.getVersion();

      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/a\",\"value\":1}]"));
      assertEquals(initial + 1, ctx.getVersion());

      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]"));
      assertEquals(initial + 2, ctx.getVersion());

      ctx.applyDiff(patch("[{\"op\":\"replace\",\"path\":\"/a\",\"value\":99}]"));
      assertEquals(initial + 3, ctx.getVersion());
    }
  }

  // ================================================================== //

  @Nested
  @DisplayName("roundtrip: snapshot → diff → applyDiff")
  class Roundtrip {

    @Test
    @DisplayName("add field roundtrip")
    void addFieldRoundtrip() {
      CaseContextImpl original = new CaseContextImpl(Map.of("x", "1"));
      CaseContext snapshot = original.snapshot();

      original.set("y", "2");
      JsonNode patch = snapshot.diff(original);

      CaseContextImpl replica = new CaseContextImpl(Map.of("x", "1"));
      replica.applyDiff(patch);

      assertEquals("1", replica.getString("x"));
      assertEquals("2", replica.getString("y"));
    }

    @Test
    @DisplayName("replace field roundtrip")
    void replaceFieldRoundtrip() {
      CaseContextImpl original = new CaseContextImpl(Map.of("status", "pending"));
      CaseContext snapshot = original.snapshot();

      original.set("status", "active");
      JsonNode patch = snapshot.diff(original);

      CaseContextImpl replica = new CaseContextImpl(Map.of("status", "pending"));
      replica.applyDiff(patch);

      assertEquals("active", replica.getString("status"));
    }

    @Test
    @DisplayName("remove field roundtrip")
    void removeFieldRoundtrip() {
      CaseContextImpl original = new CaseContextImpl(Map.of("a", "1", "b", "2"));
      CaseContext snapshot = original.snapshot();

      original.remove("b");
      JsonNode patch = snapshot.diff(original);

      CaseContextImpl replica = new CaseContextImpl(Map.of("a", "1", "b", "2"));
      replica.applyDiff(patch);

      assertTrue(replica.contains("a"));
      assertFalse(replica.contains("b"));
    }

    @Test
    @DisplayName("multiple changes roundtrip")
    void multipleChangesRoundtrip() {
      CaseContextImpl original = new CaseContextImpl(Map.of("a", "old", "b", "keep"));
      CaseContext snapshot = original.snapshot();

      original.set("a", "new");
      original.set("c", "added");
      original.remove("b");
      JsonNode patch = snapshot.diff(original);

      CaseContextImpl replica = new CaseContextImpl(Map.of("a", "old", "b", "keep"));
      replica.applyDiff(patch);

      assertEquals("new", replica.getString("a"));
      assertFalse(replica.contains("b"));
      assertEquals("added", replica.getString("c"));
    }

    @Test
    @DisplayName("sequential patches reconstruct state correctly")
    void sequentialPatches() {
      CaseContextImpl source = new CaseContextImpl(Map.of("step", 0));

      // patch 1: step 0 → 1
      CaseContext snap1 = source.snapshot();
      source.set("step", 1);
      JsonNode patch1 = snap1.diff(source);

      // patch 2: step 1 → 2 + add field
      CaseContext snap2 = source.snapshot();
      source.set("step", 2);
      source.set("done", true);
      JsonNode patch2 = snap2.diff(source);

      // replay on fresh context
      CaseContextImpl replica = new CaseContextImpl(Map.of("step", 0));
      replica.applyDiff(patch1);
      replica.applyDiff(patch2);

      assertEquals(2, replica.getInt("step"));
      assertTrue(replica.getBoolean("done"));
    }

    @Test
    @DisplayName("signal roundtrip: setPath → diff → applyDiff")
    void signalSetPathRoundtrip() {
      CaseContextImpl original = new CaseContextImpl(Map.of("balance", 100));
      CaseContext snapshot = original.snapshot();

      original.setPath("payment.amount", 250);
      JsonNode patch = snapshot.diff(original);

      CaseContextImpl replica = new CaseContextImpl(Map.of("balance", 100));
      replica.applyDiff(patch);

      assertEquals(100, replica.getInt("balance"));
      assertNotNull(replica.get("payment"));
    }
  }

  // ================================================================== //

  @Nested
  @DisplayName("existing data is preserved")
  class ExistingDataPreserved {

    @Test
    @DisplayName("should preserve pre-existing fields when adding new ones")
    void preservesExistingOnAdd() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("existing", "value"));
      ctx.applyDiff(patch("[{\"op\":\"add\",\"path\":\"/new\",\"value\":\"field\"}]"));

      assertEquals("value", ctx.getString("existing"));
      assertEquals("field", ctx.getString("new"));
    }

    @Test
    @DisplayName("should preserve all fields on empty patch")
    void preservesAllOnEmptyPatch() throws Exception {
      Map<String, Object> initial = Map.of("a", "1", "b", 2, "c", true);
      CaseContextImpl ctx = new CaseContextImpl(initial);
      ctx.applyDiff(patch("[]"));

      assertEquals("1", ctx.getString("a"));
      assertEquals(2, ctx.getInt("b"));
      assertTrue(ctx.getBoolean("c"));
    }
  }

  // ================================================================== //

  @Nested
  @DisplayName("invalid patch")
  class InvalidPatch {

    @Test
    @DisplayName("should throw when patch references a non-existent path for replace")
    void replaceNonExistentThrows() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl();
      JsonNode badPatch = patch("[{\"op\":\"replace\",\"path\":\"/missing\",\"value\":\"x\"}]");

      assertThrows(Exception.class, () -> ctx.applyDiff(badPatch));
    }

    @Test
    @DisplayName("should silently ignore remove of a non-existent path (library is lenient)")
    void removeNonExistentIsIgnored() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("existing", "value"));
      ctx.applyDiff(patch("[{\"op\":\"remove\",\"path\":\"/missing\"}]"));

      // context unchanged — library ignores the no-op
      assertEquals("value", ctx.getString("existing"));
      assertEquals(1, ctx.size());
    }

    @Test
    @DisplayName("should not mutate context state when patch fails (replace on non-existent)")
    void contextUnchangedOnFailure() throws Exception {
      CaseContextImpl ctx = new CaseContextImpl(Map.of("status", "original"));
      JsonNode badPatch = patch("[{\"op\":\"replace\",\"path\":\"/nonexistent\",\"value\":\"x\"}]");

      assertThrows(Exception.class, () -> ctx.applyDiff(badPatch));
      assertEquals("original", ctx.getString("status"));
      assertEquals(1, ctx.size());
    }
  }
}
