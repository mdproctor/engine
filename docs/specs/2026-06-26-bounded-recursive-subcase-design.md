# Bounded Recursive Sub-Case Spawning

Refs: engine#573. Blocks: devtown#11 (merge queue bisection).

## Problem

`SubCaseExecutionHandler` hard-blocks any case from spawning itself (lines 85-95). Recursive sub-cases are a legitimate use case: merge queue bisection, hierarchical approval chains, recursive document review, nested audit.

## Design

Replace the binary self-reference guard with a bounded depth check. `SubCase` gains a `maxRecursionDepth` field (int, default 0). The handler computes the current depth by walking the `parentCaseId` chain via `CaseInstanceCache`, counting all same-definition ancestors (total counting). If depth >= limit, the spawn is faulted.

### Depth semantics

`maxRecursionDepth = 0`: hard block (current behavior, backward compatible).
`maxRecursionDepth = N`: allow N levels of self-spawning, fault at N+1.

depth = count of ancestors in the parentCaseId chain with the same (namespace, name, version) as the SubCase being spawned. The spawning parent itself is not counted (it's already known to match — that's what triggered the self-reference branch). The walk counts ALL same-definition ancestors, not just consecutive ones.

| maxRecursionDepth | Recursive spawns allowed | Total instances |
|---|---|---|
| 0 | 0 | 1 (root only) |
| 1 | 1 | 2 |
| 3 | 3 | 4 |
| N | N | N+1 |

Per-chain counts. For branching recursion (M-of-N with branching factor B), total instances across all chains are O(B^D), not D+1.

### Why total counting (not consecutive)

Consecutive counting resets at non-matching ancestors, enabling a "trampoline" bypass: A(depth=3)→B→A(depth=0)→A→A→A(depth=3)→B→A(depth=0)→... creates unbounded instances. Total counting prevents this — every same-definition ancestor in the chain contributes to the depth regardless of intervening definitions.

For the common case (pure recursion A→A→A), both approaches give identical results.

### Version-strict matching

The same-definition comparison uses (namespace, name, version), matching the existing self-reference check. This means case A v1.0.0 spawning A v1.0.1 is not a self-reference and bypasses the depth check. This is correct: different versions are different definitions with potentially different structure, bindings, and semantics.

### Cache walk invariants

All ancestors in a recursive chain are in WAITING state (waiting for their child to complete) and therefore remain in `CaseInstanceCache`. `CaseInstanceCacheImpl` is a bare `ConcurrentHashMap` — no eviction, no TTL, no size limit. Cases are added at creation (`CaseHubReactor.buildInstance()`) and never individually removed (the interface has no `remove()` method; `clear()` is test-only). A cache miss during the depth walk cannot occur under the current cache lifecycle.

**Single-node assumption:** `CaseInstanceCache` is a per-JVM `ConcurrentHashMap`, not shared across nodes. The engine currently has no clustering support (RAM Quartz store, in-memory cache). In a future clustered deployment, the depth walk on node B would not find ancestors cached on node A. If clustering is added, the cache walk must be replaced with a repository query or the cache must become distributed.

**If future work adds cache eviction or node-local cache partitioning, this invariant breaks.** A cache miss during the walk would produce a lower depth than actual — fail-open (permissive), creating an unbounded recursion vulnerability. Any future eviction or clustering work must revisit this assumption.

## Changes

### 1. `SubCase.java` (api)

Add field, builder method, accessor:

```java
private final int maxRecursionDepth; // 0 = no recursion (default)
```

Constructor enforces `maxRecursionDepth >= 0` via `IllegalArgumentException`.

### 2. `CaseDefinition.yaml` schema

Add to SubCase definition:

```yaml
maxRecursionDepth:
  type: integer
  minimum: 0
  maximum: 20
  default: 0
  description: "Maximum self-referencing depth. 0 = no recursion (default). N = allow N levels."
```

The cap of 20 bounds the maximum hierarchy depth for YAML-authored definitions. For merge queue bisection (the motivating use case), log₂(1M) ≈ 20 — sufficient for queues of over a million items. The cap also prevents YAML typos (2000 instead of 2) from creating runaway hierarchies. The API model does not enforce an upper bound — programmatic definitions may legitimately need higher values for linear recursion patterns where total instances = depth + 1.

### 3. `CaseDefinitionYamlMapper.convertSubCase()`

Map the new field from the generated schema model to the API model. Null defaults to 0.

### 4. `SubCaseExecutionHandler`

Inject `CaseInstanceCache` (already used in the blackboard module by `SubCaseCompletionService`).

Replace the hard circular guard (lines 86-95) with:

```
1. Detect self-reference (same check as today)
2. If self-reference: compute depth (short-circuiting at maxRecursionDepth),
   if depth >= maxRecursionDepth → fault; else allow
```

No special case for `maxRecursionDepth <= 0`. The general path subsumes it: depth starts at 0, the short-circuit condition `depth < maxRecursionDepth` is false when maxRecursionDepth is 0 (0 < 0 = false), so the walk never executes and depth remains 0. The check `0 >= 0` faults immediately. Zero additional cost versus a dedicated branch.

The depth computation method:

```java
private int computeSameDefinitionDepth(CaseInstance parent, SubCase subCase, int maxDepth) {
    int depth = 0;
    UUID ancestorId = parent.getParentCaseId();
    while (ancestorId != null && depth < maxDepth) {
        CaseInstance ancestor = caseInstanceCache.get(ancestorId);
        if (ancestor == null) break;
        CaseMetaModel meta = ancestor.getCaseMetaModel();
        if (meta != null
            && subCase.namespace().equals(meta.getNamespace())
            && subCase.name().equals(meta.getName())
            && subCase.version().equals(meta.getVersion())) {
            depth++;
        }
        ancestorId = ancestor.getParentCaseId();
    }
    return depth;
}
```

The `depth < maxDepth` loop guard provides short-circuiting: once depth reaches the limit, further walking is unnecessary. For maxRecursionDepth=0 the loop body never executes.

### 5. Tests

New `SubCaseRecursionDepthTest` (unit tests). Depth walk tests use an in-memory `CaseInstanceCacheImpl` (production implementation — zero dependencies, trivially instantiable) with real `CaseInstance` objects to build parent chains. `CaseHubRuntime` and repositories remain mocked.

1. **depth 0 preserves hard block** — self-reference with maxRecursionDepth=0 → PlanItem FAULTED
2. **depth N allows N levels** — chain of N self-referencing spawns all succeed (PlanItems DELEGATED)
3. **the (N+1)th self-referencing spawn faults** — depth reaches maxRecursionDepth → PlanItem FAULTED
4. **non-self-referencing spawn ignores maxRecursionDepth** — SubCase targeting a different definition bypasses the check entirely
5. **cache miss stops walk (defensive)** — deliberately remove an ancestor from cache to verify the walk stops early and faults at a lower depth. This scenario cannot occur under the current cache lifecycle (no eviction, no remove); the test documents the fail-open (permissive) behavior as a defensive specification for future-proofing.
6. **total counting across non-matching ancestors** — A→B→A chain counts both A ancestors, not just the consecutive one

Update existing `circular_dependency_marks_plan_item_faulted` test for maxRecursionDepth=0 semantics. Update `SubCaseExecutionHandlerTest.setUp()` for new `CaseInstanceCache` constructor parameter.

## Known limitations

**Mutual recursion is unbounded.** The depth check only fires on self-reference — when the parent's (namespace, name, version) matches the child SubCase's identity. Mutual recursion (A→B→A→B→...) bypasses this check entirely: B spawning A is not a self-reference from B's perspective, so `maxRecursionDepth` is never consulted on B's spawn. This creates an unbounded spawn vulnerability if two case definitions reference each other. Cycle detection for multi-definition chains would require walking the ancestor chain regardless of definition match and tracking all visited definitions — a different feature with different performance characteristics. Not in scope for this issue.

## Files changed

| File | Change |
|---|---|
| `api/.../SubCase.java` | Add `maxRecursionDepth` field, builder method, accessor |
| `schema/.../CaseDefinition.yaml` | Add `maxRecursionDepth` to SubCase schema |
| `api/.../converter/CaseDefinitionYamlMapper.java` | Map `maxRecursionDepth` in `convertSubCase()` |
| `blackboard/.../SubCaseExecutionHandler.java` | Inject `CaseInstanceCache`, replace guard with bounded check |
| `blackboard/.../SubCaseExecutionHandlerTest.java` | Update existing `circular_dependency` test, update setUp for cache |
| `blackboard/.../SubCaseRecursionDepthTest.java` | New test class (6 test cases) |

## Not changed

- `CaseInstance` — no new fields, no persistence changes
- `CaseInstanceEntity` — no JPA changes
- `CaseHubReactor` — no changes to case creation
- `PropagationContext` — no depth tracking; carries trace/budget/attributes but no definition awareness
