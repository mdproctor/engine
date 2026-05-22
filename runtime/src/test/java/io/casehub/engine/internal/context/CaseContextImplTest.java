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

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link CaseContextImpl} — methods not covered by {@link
 * CaseContextImplApplyDiffTest}.
 *
 * <p>Focused on: set/get, typed accessors, compareAndSet, update, merge, getPath/setPath,
 * computeIfAbsent, putIfAbsent, getAll/setAll, clear, version semantics.
 */
@DisplayName("StateContextImpl")
class CaseContextImplTest {

  // ================================================================== //
  //  Construction                                                       //
  // ================================================================== //

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("empty constructor creates empty context at version 0")
    void empty_createsEmptyContext() {
      final var ctx = new CaseContextImpl();
      assertTrue(ctx.isEmpty());
      assertEquals(0, ctx.getVersion());
    }

    @Test
    @DisplayName("map constructor populates data without incrementing version")
    void mapConstructor_populatesData() {
      final var ctx = new CaseContextImpl(Map.of("a", "1", "b", 2));
      assertEquals("1", ctx.getString("a"));
      assertEquals(2, ctx.getInt("b"));
      // version stays 0 because the constructor doesn't call set()
      assertEquals(0, ctx.getVersion());
    }
  }

  // ================================================================== //
  //  set / get / version                                                //
  // ================================================================== //

  @Nested
  @DisplayName("set / get / version")
  class SetGetVersion {

    @Test
    @DisplayName("set increments version on first write")
    void set_incrementsVersion() {
      final var ctx = new CaseContextImpl();
      ctx.set("k", "v");
      assertEquals(1, ctx.getVersion());
    }

    @Test
    @DisplayName("setting the same value twice does not increment version the second time")
    void set_sameValue_noVersionIncrement() {
      final var ctx = new CaseContextImpl();
      ctx.set("k", "v");
      final long v1 = ctx.getVersion();
      ctx.set("k", "v");
      assertEquals(v1, ctx.getVersion(), "no-op set must not increment version");
    }

    @Test
    @DisplayName("setting a different value increments version again")
    void set_differentValue_incrementsAgain() {
      final var ctx = new CaseContextImpl();
      ctx.set("k", "a");
      final long v1 = ctx.getVersion();
      ctx.set("k", "b");
      assertEquals(v1 + 1, ctx.getVersion());
    }

    @Test
    @DisplayName("get returns null for absent key")
    void get_absentKey_null() {
      final var ctx = new CaseContextImpl();
      assertNull(ctx.get("missing"));
    }

    @Test
    @DisplayName("get returns value for present key")
    void get_presentKey_returnsValue() {
      final var ctx = new CaseContextImpl(Map.of("x", "hello"));
      assertEquals("hello", ctx.get("x"));
    }

    @Test
    @DisplayName("contains returns true for present key, false for absent")
    void contains_presentAndAbsent() {
      final var ctx = new CaseContextImpl(Map.of("x", "y"));
      assertTrue(ctx.contains("x"));
      assertFalse(ctx.contains("z"));
    }

    @Test
    @DisplayName("remove decrements size and increments version")
    void remove_presentKey() {
      final var ctx = new CaseContextImpl(Map.of("a", "1"));
      ctx.remove("a");
      assertFalse(ctx.contains("a"));
      assertEquals(1, ctx.getVersion());
    }

    @Test
    @DisplayName("remove absent key is a no-op (version unchanged)")
    void remove_absentKey_noOp() {
      final var ctx = new CaseContextImpl(Map.of("a", "1"));
      final long before = ctx.getVersion();
      ctx.remove("missing");
      assertEquals(before, ctx.getVersion());
    }
  }

  // ================================================================== //
  //  Typed accessors                                                    //
  // ================================================================== //

  @Nested
  @DisplayName("typed accessors")
  class TypedAccessors {

    @Test
    @DisplayName("getString returns null for absent key")
    void getString_absent_null() {
      assertEquals(null, new CaseContextImpl().getString("k"));
    }

    @Test
    @DisplayName("getString converts int to string")
    void getString_int_convertsToString() {
      final var ctx = new CaseContextImpl(Map.of("n", 42));
      assertEquals("42", ctx.getString("n"));
    }

    @Test
    @DisplayName("getInt parses integer from string")
    void getInt_stringValue_parsed() {
      final var ctx = new CaseContextImpl(Map.of("n", "7"));
      assertEquals(7, ctx.getInt("n"));
    }

    @Test
    @DisplayName("getInt returns null for absent key")
    void getInt_absent_null() {
      assertNull(new CaseContextImpl().getInt("n"));
    }

    @Test
    @DisplayName("getLong returns long value")
    void getLong_returnsLong() {
      final var ctx = new CaseContextImpl(Map.of("n", 123456789012345L));
      assertEquals(123456789012345L, ctx.getLong("n"));
    }

    @Test
    @DisplayName("getLong parses from string")
    void getLong_stringValue_parsed() {
      final var ctx = new CaseContextImpl(Map.of("n", "9999999999"));
      assertEquals(9999999999L, ctx.getLong("n"));
    }

    @Test
    @DisplayName("getLong returns null for absent key")
    void getLong_absent_null() {
      assertNull(new CaseContextImpl().getLong("n"));
    }

    @Test
    @DisplayName("getDouble returns double value")
    void getDouble_returnsDouble() {
      final var ctx = new CaseContextImpl(Map.of("d", 3.14));
      assertEquals(3.14, ctx.getDouble("d"), 0.001);
    }

    @Test
    @DisplayName("getDouble parses from string")
    void getDouble_stringValue_parsed() {
      final var ctx = new CaseContextImpl(Map.of("d", "2.71"));
      assertEquals(2.71, ctx.getDouble("d"), 0.001);
    }

    @Test
    @DisplayName("getDouble returns null for absent key")
    void getDouble_absent_null() {
      assertNull(new CaseContextImpl().getDouble("d"));
    }

    @Test
    @DisplayName("getBoolean returns true for Boolean.TRUE")
    void getBoolean_trueValue() {
      final var ctx = new CaseContextImpl(Map.of("b", Boolean.TRUE));
      assertTrue(ctx.getBoolean("b"));
    }

    @Test
    @DisplayName("getBoolean returns false for Boolean.FALSE")
    void getBoolean_falseValue() {
      final var ctx = new CaseContextImpl(Map.of("b", Boolean.FALSE));
      assertFalse(ctx.getBoolean("b"));
    }

    @Test
    @DisplayName("getBoolean parses 'true' string")
    void getBoolean_stringTrue_parsed() {
      final var ctx = new CaseContextImpl(Map.of("b", "true"));
      assertTrue(ctx.getBoolean("b"));
    }

    @Test
    @DisplayName("getBoolean returns null for absent key")
    void getBoolean_absent_null() {
      assertNull(new CaseContextImpl().getBoolean("b"));
    }

    @Test
    @DisplayName("getOrDefault returns stored value when present")
    void getOrDefault_present_returnsStored() {
      final var ctx = new CaseContextImpl(Map.of("k", "stored"));
      assertEquals("stored", ctx.getOrDefault("k", "default"));
    }

    @Test
    @DisplayName("getOrDefault returns default when key absent")
    void getOrDefault_absent_returnsDefault() {
      final var ctx = new CaseContextImpl();
      assertEquals("default", ctx.getOrDefault("k", "default"));
    }

    @Test
    @DisplayName("getAs converts numeric to target type via Jackson")
    void getAs_numericConversion() {
      final var ctx = new CaseContextImpl(Map.of("n", 42));
      assertEquals(Long.valueOf(42L), ctx.getAs("n", Long.class));
    }
  }

  // ================================================================== //
  //  compareAndSet                                                      //
  // ================================================================== //

  @Nested
  @DisplayName("compareAndSet")
  class CompareAndSetTests {

    @Test
    @DisplayName("succeeds when current value matches expected")
    void cas_match_returnsTrue() {
      final var ctx = new CaseContextImpl(Map.of("status", "pending"));
      final boolean result = ctx.compareAndSet("status", "pending", "active");
      assertTrue(result);
      assertEquals("active", ctx.getString("status"));
    }

    @Test
    @DisplayName("fails when current value does not match expected")
    void cas_mismatch_returnsFalse() {
      final var ctx = new CaseContextImpl(Map.of("status", "running"));
      final boolean result = ctx.compareAndSet("status", "pending", "active");
      assertFalse(result);
      assertEquals("running", ctx.getString("status"), "value must be unchanged after failed CAS");
    }

    @Test
    @DisplayName("succeeds for absent key when expected is null")
    void cas_nullExpected_absentKey_succeeds() {
      final var ctx = new CaseContextImpl();
      final boolean result = ctx.compareAndSet("k", null, "new-value");
      assertTrue(result);
      assertEquals("new-value", ctx.getString("k"));
    }

    @Test
    @DisplayName("does not increment version when new value equals current value")
    void cas_sameValue_noVersionIncrement() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final long before = ctx.getVersion();
      ctx.compareAndSet("k", "v", "v"); // same value
      assertEquals(before, ctx.getVersion(), "no-op CAS must not increment version");
    }

    @Test
    @DisplayName("increments version on successful value change")
    void cas_successfulChange_incrementsVersion() {
      final var ctx = new CaseContextImpl(Map.of("k", "old"));
      final long before = ctx.getVersion();
      ctx.compareAndSet("k", "old", "new");
      assertEquals(before + 1, ctx.getVersion());
    }
  }

  // ================================================================== //
  //  update                                                             //
  // ================================================================== //

  @Nested
  @DisplayName("update")
  class UpdateTests {

    @Test
    @DisplayName("update applies function to current value")
    void update_transformsValue() {
      final var ctx = new CaseContextImpl(Map.of("count", 5));
      ctx.update("count", v -> ((Number) v).intValue() + 1);
      assertEquals(6, ctx.getInt("count"));
    }

    @Test
    @DisplayName("update with null result removes the key")
    void update_nullResult_removesKey() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      ctx.update("k", v -> null);
      assertFalse(ctx.contains("k"));
    }

    @Test
    @DisplayName("update with no-op (same value) does not increment version")
    void update_noOpSameValue_noVersionIncrement() {
      final var ctx = new CaseContextImpl(Map.of("k", "same"));
      final long before = ctx.getVersion();
      ctx.update("k", v -> "same");
      assertEquals(before, ctx.getVersion());
    }

    @Test
    @DisplayName("update receives null for absent key")
    void update_absentKey_receivesNull() {
      final var ctx = new CaseContextImpl();
      final Object[] received = {new Object()}; // sentinel
      ctx.update(
          "absent",
          v -> {
            received[0] = v;
            return null;
          });
      assertNull(received[0]);
    }
  }

  // ================================================================== //
  //  computeIfAbsent / putIfAbsent                                      //
  // ================================================================== //

  @Nested
  @DisplayName("computeIfAbsent / putIfAbsent")
  class AbsentOperations {

    @Test
    @DisplayName("computeIfAbsent computes and stores when key absent")
    void computeIfAbsent_absent_computesAndStores() {
      final var ctx = new CaseContextImpl();
      final Object result = ctx.computeIfAbsent("k", key -> "computed");
      assertEquals("computed", result);
      assertEquals("computed", ctx.getString("k"));
    }

    @Test
    @DisplayName("computeIfAbsent returns existing value without calling function")
    void computeIfAbsent_present_returnsExisting() {
      final var ctx = new CaseContextImpl(Map.of("k", "existing"));
      final boolean[] called = {false};
      final Object result =
          ctx.computeIfAbsent(
              "k",
              key -> {
                called[0] = true;
                return "computed";
              });
      assertEquals("existing", result);
      assertFalse(called[0], "mapping function must not be called when key is present");
    }

    @Test
    @DisplayName("computeIfAbsent does not store when function returns null")
    void computeIfAbsent_functionReturnsNull_notStored() {
      final var ctx = new CaseContextImpl();
      final long before = ctx.getVersion();
      ctx.computeIfAbsent("k", key -> null);
      assertFalse(ctx.contains("k"));
      assertEquals(before, ctx.getVersion(), "no-op must not increment version");
    }

    @Test
    @DisplayName("putIfAbsent stores value when key absent")
    void putIfAbsent_absent_stores() {
      final var ctx = new CaseContextImpl();
      final Object previous = ctx.putIfAbsent("k", "new");
      assertNull(previous, "previous should be null when key was absent");
      assertEquals("new", ctx.getString("k"));
    }

    @Test
    @DisplayName("putIfAbsent returns existing value without overwriting")
    void putIfAbsent_present_returnsExisting() {
      final var ctx = new CaseContextImpl(Map.of("k", "existing"));
      final Object previous = ctx.putIfAbsent("k", "new");
      assertEquals("existing", previous);
      assertEquals("existing", ctx.getString("k"), "existing value must not be overwritten");
    }
  }

  // ================================================================== //
  //  setAll / getAll                                                    //
  // ================================================================== //

  @Nested
  @DisplayName("setAll / getAll")
  class SetAllGetAll {

    @Test
    @DisplayName("setAll stores all provided key-value pairs")
    void setAll_storesAll() {
      final var ctx = new CaseContextImpl();
      ctx.setAll(Map.of("a", "1", "b", "2"));
      assertEquals("1", ctx.getString("a"));
      assertEquals("2", ctx.getString("b"));
    }

    @Test
    @DisplayName("setAll with null map is a no-op")
    void setAll_nullMap_noOp() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final long before = ctx.getVersion();
      ctx.setAll(null);
      assertEquals(before, ctx.getVersion());
    }

    @Test
    @DisplayName("setAll with empty map is a no-op")
    void setAll_emptyMap_noOp() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final long before = ctx.getVersion();
      ctx.setAll(Map.of());
      assertEquals(before, ctx.getVersion());
    }

    @Test
    @DisplayName("setAll only increments version once for multiple changes")
    void setAll_multipleChanges_singleVersionIncrement() {
      final var ctx = new CaseContextImpl();
      final long before = ctx.getVersion();
      ctx.setAll(Map.of("a", "1", "b", "2", "c", "3"));
      assertEquals(before + 1, ctx.getVersion(), "setAll should increment version exactly once");
    }

    @Test
    @DisplayName("setAll no-op when all values are identical")
    void setAll_allSameValues_noVersionIncrement() {
      final var ctx = new CaseContextImpl(Map.of("a", "1"));
      final long before = ctx.getVersion();
      ctx.setAll(Map.of("a", "1")); // no actual change
      assertEquals(before, ctx.getVersion());
    }

    @Test
    @DisplayName("getAll returns only present keys")
    void getAll_returnsOnlyPresentKeys() {
      final var ctx = new CaseContextImpl(Map.of("a", "1", "b", "2"));
      final var result = ctx.getAll("a", "c"); // 'c' is missing
      assertEquals(1, result.size());
      assertEquals("1", result.get("a"));
      assertFalse(result.containsKey("c"));
    }

    @Test
    @DisplayName("getAll with no matching keys returns empty map")
    void getAll_noMatchingKeys_empty() {
      final var ctx = new CaseContextImpl(Map.of("a", "1"));
      final var result = ctx.getAll("x", "y");
      assertTrue(result.isEmpty());
    }
  }

  // ================================================================== //
  //  clear                                                              //
  // ================================================================== //

  @Nested
  @DisplayName("clear")
  class ClearTests {

    @Test
    @DisplayName("clear empties the context and increments version")
    void clear_emptiesContext() {
      final var ctx = new CaseContextImpl(Map.of("a", "1", "b", "2"));
      ctx.clear();
      assertTrue(ctx.isEmpty());
      assertEquals(0, ctx.size());
      assertEquals(1, ctx.getVersion());
    }

    @Test
    @DisplayName("clear on already-empty context is a no-op")
    void clear_alreadyEmpty_noOp() {
      final var ctx = new CaseContextImpl();
      final long before = ctx.getVersion();
      ctx.clear();
      assertEquals(before, ctx.getVersion(), "clearing empty context must not increment version");
    }
  }

  // ================================================================== //
  //  getPath / setPath                                                  //
  // ================================================================== //

  @Nested
  @DisplayName("getPath / setPath")
  class PathOperations {

    @Test
    @DisplayName("setPath creates nested structure and getPath retrieves it")
    void setPath_createsNested_getPathRetrieve() {
      final var ctx = new CaseContextImpl();
      ctx.setPath("payment.amount", 500);
      assertEquals(500, ctx.getPath("payment.amount"));
    }

    @Test
    @DisplayName("setPath creates intermediate map nodes")
    void setPath_createsIntermediateNodes() {
      final var ctx = new CaseContextImpl();
      ctx.setPath("a.b.c", "deep");
      assertEquals("deep", ctx.getPath("a.b.c"));
    }

    @Test
    @DisplayName("getPath returns null for absent top-level key")
    void getPath_absentTopLevel_null() {
      final var ctx = new CaseContextImpl();
      assertNull(ctx.getPath("missing.path"));
    }

    @Test
    @DisplayName("getPath returns null when intermediate key is absent")
    void getPath_absentIntermediate_null() {
      final var ctx = new CaseContextImpl(Map.of("a", Map.of("x", "1")));
      assertNull(ctx.getPath("a.b.c"));
    }

    @Test
    @DisplayName("setPath on non-map node throws IllegalStateException")
    void setPath_nonMapNode_throws() {
      final var ctx = new CaseContextImpl(Map.of("a", "scalar"));
      assertThrows(
          IllegalStateException.class,
          () -> ctx.setPath("a.b", "value"),
          "cannot set path through a non-map node");
    }

    @Test
    @DisplayName("getPathAsString returns string representation")
    void getPathAsString_returnsString() {
      final var ctx = new CaseContextImpl();
      ctx.setPath("x.y", 42);
      assertEquals("42", ctx.getPathAsString("x.y"));
    }

    @Test
    @DisplayName("getPathAsString returns null for absent path")
    void getPathAsString_absent_null() {
      assertNull(new CaseContextImpl().getPathAsString("missing"));
    }

    @Test
    @DisplayName("setPath with same value does not increment version")
    void setPath_sameValue_noVersionIncrement() {
      final var ctx = new CaseContextImpl();
      ctx.setPath("a.b", "v");
      final long before = ctx.getVersion();
      ctx.setPath("a.b", "v");
      assertEquals(before, ctx.getVersion());
    }
  }

  // ================================================================== //
  //  merge                                                              //
  // ================================================================== //

  @Nested
  @DisplayName("merge")
  class MergeTests {

    @Test
    @DisplayName("merge adds new keys from other context")
    void merge_addsNewKeys() {
      final var ctx = new CaseContextImpl(Map.of("a", "1"));
      final var other = new CaseContextImpl(Map.of("b", "2"));
      ctx.merge(other);
      assertEquals("1", ctx.getString("a"));
      assertEquals("2", ctx.getString("b"));
    }

    @Test
    @DisplayName("merge overwrites existing keys with values from other")
    void merge_overwritesExisting() {
      final var ctx = new CaseContextImpl(Map.of("k", "old"));
      final var other = new CaseContextImpl(Map.of("k", "new"));
      ctx.merge(other);
      assertEquals("new", ctx.getString("k"));
    }

    @Test
    @DisplayName("merge with null is a no-op")
    void merge_null_noOp() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final long before = ctx.getVersion();
      ctx.merge(null);
      assertEquals(before, ctx.getVersion());
      assertEquals("v", ctx.getString("k"));
    }

    @Test
    @DisplayName("merge with empty context is a no-op")
    void merge_emptyOther_noOp() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final long before = ctx.getVersion();
      ctx.merge(new CaseContextImpl());
      assertEquals(before, ctx.getVersion());
    }

    @Test
    @DisplayName("merge with identical values does not increment version")
    void merge_sameValues_noVersionIncrement() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final var other = new CaseContextImpl(Map.of("k", "v"));
      final long before = ctx.getVersion();
      ctx.merge(other);
      assertEquals(before, ctx.getVersion());
    }
  }

  // ================================================================== //
  //  snapshot                                                           //
  // ================================================================== //

  @Nested
  @DisplayName("snapshot")
  class SnapshotTests {

    @Test
    @DisplayName("snapshot captures current data at current version")
    void snapshot_capturesState() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final var snap = (CaseContextImpl) ctx.snapshot();
      assertEquals("v", snap.getString("k"));
      assertEquals(ctx.getVersion(), snap.getVersion());
    }

    @Test
    @DisplayName("modifying original after snapshot does not affect snapshot")
    void snapshot_isIndependent() {
      final var ctx = new CaseContextImpl(Map.of("k", "original"));
      final var snap = (CaseContextImpl) ctx.snapshot();
      ctx.set("k", "modified");
      assertEquals("original", snap.getString("k"), "snapshot must be independent of original");
    }

    @Test
    @DisplayName("modifying snapshot does not affect original")
    void snapshot_modifySnap_doesNotAffectOriginal() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final var snap = (CaseContextImpl) ctx.snapshot();
      snap.set("k", "changed");
      assertEquals("v", ctx.getString("k"), "original must be unaffected by snapshot changes");
    }
  }

  // ================================================================== //
  //  getKeys / size / isEmpty                                           //
  // ================================================================== //

  @Nested
  @DisplayName("getKeys / size / isEmpty")
  class InspectionTests {

    @Test
    @DisplayName("getKeys returns all stored keys")
    void getKeys_returnsAllKeys() {
      final var ctx = new CaseContextImpl(Map.of("a", "1", "b", "2"));
      final var keys = ctx.getKeys();
      assertEquals(2, keys.size());
      assertTrue(keys.contains("a"));
      assertTrue(keys.contains("b"));
    }

    @Test
    @DisplayName("size returns count of stored entries")
    void size_returnsCount() {
      final var ctx = new CaseContextImpl(Map.of("a", "1", "b", "2", "c", "3"));
      assertEquals(3, ctx.size());
    }

    @Test
    @DisplayName("isEmpty returns true for empty context")
    void isEmpty_empty_true() {
      assertTrue(new CaseContextImpl().isEmpty());
    }

    @Test
    @DisplayName("isEmpty returns false after adding entry")
    void isEmpty_afterAdd_false() {
      final var ctx = new CaseContextImpl();
      ctx.set("k", "v");
      assertFalse(ctx.isEmpty());
    }
  }

  // ================================================================== //
  //  asJsonNode / getData                                               //
  // ================================================================== //

  @Nested
  @DisplayName("asJsonNode / getData")
  class JsonNodeTests {

    @Test
    @DisplayName("asJsonNode returns an object node with all entries")
    void asJsonNode_returnsObjectNode() {
      final var ctx = new CaseContextImpl(Map.of("status", "active", "count", 3));
      final var node = ctx.asJsonNode();
      assertNotNull(node);
      assertEquals("active", node.get("status").asText());
      assertEquals(3, node.get("count").asInt());
    }

    @Test
    @DisplayName("getData returns a copy — modifying it does not affect context")
    void getData_returnsCopy() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      final var data = ctx.getData();
      data.put("k", "modified");
      assertEquals("v", ctx.getString("k"), "getData must return a defensive copy");
    }
  }

  // ================================================================== //
  //  equals / hashCode                                                  //
  // ================================================================== //

  @Nested
  @DisplayName("equals / hashCode")
  class EqualsHashCode {

    @Test
    @DisplayName("two contexts with same data are equal regardless of version")
    void equalData_equalContexts() {
      final var a = new CaseContextImpl(Map.of("k", "v"), 1L);
      final var b = new CaseContextImpl(Map.of("k", "v"), 99L);
      assertEquals(a, b, "equality is based on data, not version");
    }

    @Test
    @DisplayName("contexts with different data are not equal")
    void differentData_notEqual() {
      final var a = new CaseContextImpl(Map.of("k", "v1"));
      final var b = new CaseContextImpl(Map.of("k", "v2"));
      assertFalse(a.equals(b));
    }

    @Test
    @DisplayName("same instance equals itself")
    void sameInstance_equal() {
      final var ctx = new CaseContextImpl(Map.of("k", "v"));
      assertTrue(ctx.equals(ctx));
    }

    @Test
    @DisplayName("hashCode consistent with equals")
    void hashCode_consistentWithEquals() {
      final var a = new CaseContextImpl(Map.of("k", "v"));
      final var b = new CaseContextImpl(Map.of("k", "v"));
      assertEquals(a.hashCode(), b.hashCode());
    }
  }

  // ================================================================== //
  //  toString                                                           //
  // ================================================================== //

  @Nested
  @DisplayName("toString")
  class ToStringTests {

    @Test
    @DisplayName("toString returns valid JSON representation")
    void toString_returnsJson() {
      final var ctx = new CaseContextImpl(Map.of("status", "ok"));
      final var json = ctx.toString();
      assertNotNull(json);
      assertTrue(json.contains("status"), "toString should contain the key");
      assertTrue(json.contains("ok"), "toString should contain the value");
    }
  }
}
