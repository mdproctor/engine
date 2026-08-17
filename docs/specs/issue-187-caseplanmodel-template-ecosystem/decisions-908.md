# Decisions — engine#908: YAML overlay removal syntax

## D1: Scope of remove: directive

**Choice:** Generic — `YamlMerger` processes `remove:` at any object level during merge. `remove:` is a reserved key everywhere. Reusable for ops-deployment and other consumers.
**Alternatives:**
- Spec-scoped only — only `spec.remove:` processed, simpler but less reusable
- Configurable key name — caller specifies the remove key, avoids conflicts with domains using `remove` as a real field
**Rationale:** `YamlMerger` is a platform utility in `platform-api`. Generic processing keeps it domain-agnostic. If a domain uses `remove` as a real field name, that's an unlikely collision — and the configurable key field overload already exists as a pattern (`merge(base, overlay, keyField)`), so a `removeKey` parameter could be added later if needed.
**Trade-offs:** `remove` becomes a reserved key in overlay YAML. Acceptable — no existing CaseHub YAML uses `remove` as a field name.
**Sources:** engine#908 issue body, YamlMerger.java
**Exploration:** quick
**Status:** captured

## D2: Pipeline position for remove: processing

**Choice:** Pre-extract + post-merge filter. Extract `remove:` directives from the overlay before merging. Merge normally. Then filter named arrays in the merged result. The `remove:` key never appears in the output.
**Alternatives:**
- Inline in `mergeNamedArrays()` — simpler code but mixes merge and removal concerns
- Two-pass merge — merge first (remove: survives), clean up afterward. Simpler extraction but key briefly pollutes the output.
**Rationale:** Clean separation — merge logic stays untouched, removal is a testable post-processing step. Pre-extraction prevents `remove:` from leaking into the merged tree and reaching `CaseDefinitionYamlMapper`.
**Trade-offs:** Slightly more code (separate extraction + filtering methods). Acceptable for cleanliness.
**Sources:** YamlMerger.java current implementation
**Exploration:** quick
**Status:** captured
