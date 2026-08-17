# YAML Overlay Removal Syntax for Name-Keyed Array Merge

**Issue:** casehubio/engine#908
**Date:** 2026-08-17
**Status:** Draft

## Problem

`YamlMerger` (casehub-platform-api) supports override (same name = deep merge) and add (new name = append) for named arrays. There is no declarative way to remove an element from a base template's array. Consumers must use Java `augment()` code:

```java
definition.getBindings().removeIf(b -> b.getName().equals("unwanted"));
```

This breaks the "no Java code" promise for operators who want fully declarative composition.

## Solution

The overlay YAML can include a `remove:` object at any nesting level. Its keys are sibling array field names; its values are lists of element names to remove from those arrays after merge.

### YAML syntax

```yaml
spec:
  remove:
    bindings: [precedent-security-review, precedent-architecture-review]
    workers: [unused-worker]
  bindings:
    - name: human-approval
      humanTask:
        expiresIn: PT48H
```

The `remove:` directive and the regular merge overrides coexist at the same level. Merge runs first (adding/overriding elements), then removal filters the result.

### Processing pipeline

Three steps inside `YamlMerger.merge()`:

1. **Extract:** Before `mergeObjects()`, scan the overlay for `remove:` keys at each object level. Collect removal directives into a map keyed by array field name → set of element names. Strip `remove:` from the overlay so it does not participate in the merge.

2. **Merge:** Normal deep merge (unchanged — `mergeObjects()`, `mergeNamedArrays()` as before).

3. **Filter:** After merge, apply collected removals at each level — iterate the removal map, find the sibling array in the merged result, filter out elements whose key field value is in the removal set.

### API

No new public methods. The existing `merge(JsonNode, JsonNode)` and `merge(JsonNode, JsonNode, String)` overloads handle `remove:` transparently. The `remove` key is documented as reserved in overlay YAML.

### Scope

Generic — `remove:` is processed at any object level during merge, not just under `spec:`. This makes it reusable for ops-deployment manifests, agent profiles, and any other YAML composition that uses `YamlMerger`.

### Edge cases

| Scenario | Behavior |
|----------|----------|
| `remove:` targets a non-existent sibling array | Silently ignored |
| `remove:` targets a non-named array (string list) | Silently ignored |
| Name in removal list doesn't exist in the array | Silently ignored |
| `remove:` in the base YAML (not overlay) | Not processed — only overlay removals apply |
| `remove:` at nested object levels | Processed recursively — works at any depth |
| `remove:` combined with override of the same element | Override runs first, removal filters second — element is removed |

### Implementation

Changes to `YamlMerger.java` only. No changes to `YamlCaseHub`, `CaseDefinitionYamlMapper`, or any consumer.

```java
// New private method — extract remove: directives from overlay
private static Map<String, Set<String>> extractRemovals(ObjectNode overlay) {
    JsonNode removeNode = overlay.remove("remove");
    if (removeNode == null || !removeNode.isObject()) return Map.of();
    Map<String, Set<String>> removals = new LinkedHashMap<>();
    removeNode.fields().forEachRemaining(entry -> {
        if (entry.getValue().isArray()) {
            Set<String> names = new LinkedHashSet<>();
            entry.getValue().forEach(n -> names.add(n.asText()));
            removals.put(entry.getKey(), names);
        }
    });
    return removals;
}

// New private method — apply removals to merged result
private static void applyRemovals(
        ObjectNode merged, Map<String, Set<String>> removals, String keyField) {
    for (var entry : removals.entrySet()) {
        JsonNode arrayNode = merged.get(entry.getKey());
        if (arrayNode == null || !arrayNode.isArray()) continue;
        ArrayNode filtered = merged.arrayNode();
        for (JsonNode element : arrayNode) {
            if (element.isObject() && element.has(keyField)) {
                if (!entry.getValue().contains(element.get(keyField).asText())) {
                    filtered.add(element);
                }
            } else {
                filtered.add(element);
            }
        }
        merged.set(entry.getKey(), filtered);
    }
}
```

The `mergeObjects()` method gains two lines:
```java
private static ObjectNode mergeObjects(ObjectNode base, ObjectNode overlay, String keyField) {
    ObjectNode overlayCopy = overlay.deepCopy();
    Map<String, Set<String>> removals = extractRemovals(overlayCopy);
    ObjectNode result = base.deepCopy();
    // ... existing merge loop using overlayCopy instead of overlay ...
    applyRemovals(result, removals, keyField);
    return result;
}
```

Note: `overlay.deepCopy()` before extraction ensures the caller's overlay node is not mutated. `extractRemovals()` calls `overlay.remove("remove")` which mutates the copy.

## Testing

Pure unit tests in `YamlMergerTest.java`. No CDI, no Quarkus.

- Remove single element from named array
- Remove multiple elements from same array
- Remove from multiple arrays at same level (`remove: { bindings: [...], workers: [...] }`)
- Remove at nested level (`spec.remove.bindings`)
- Remove non-existent element — silently ignored
- Remove from non-existent array — silently ignored
- Remove combined with override of the same element — element removed
- Remove combined with add — added element can also be removed
- No `remove:` key — existing behavior unchanged
- `remove:` in base (not overlay) — not processed

## References

- casehubio/engine#908 — this issue
- casehubio/devtown#187 — parent work (YAML overlay/merge v1)
- `YamlMerger.java` in `casehub-platform-api` — target file
- D3 in devtown#187 decisions — original deferral rationale
- Decisions: `decisions-908.md` in this spec directory (D1–D2)
