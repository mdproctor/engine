# Decisions — Case Definition YAML Overlay/Merge

## D1: Target user and distribution model

**Choice:** Both Java developers and operators (layered). Maven-first for Java developers now; YAML-only import layer deferred.
**Alternatives:**
- Java developers only — limits adoption to Java-capable teams
- Operators (no-code) only — requires building a full YAML import/discovery layer before any value
**Rationale:** Maven + CDI already solve distribution and discovery. Building on what exists delivers value immediately. The YAML-only operator path is enabled by the overlay mechanism but doesn't need its own infrastructure.
**Trade-offs:** Operators still need a Maven dependency (not a Git URL import). Acceptable for v1.
**Exploration:** quick
**Status:** captured

## D2: Composition model

**Choice:** YAML deep merge. App provides a partial YAML that deep-merges over the base YAML.
**Alternatives:**
- augment() only — no YAML merging, all customisation in Java code
- Named extension points — templates declare explicit slots; apps fill them
- Layered YAML + augment() — three YAML layers (template > app > defaults) then Java
**Rationale:** Deep merge is the simplest model that gives full declarative customisation. Maps merge by key override; arrays merge by name. Well-understood pattern (Helm values.yaml, Kustomize). augment() still works as the final Java layer.
**Trade-offs:** Merge semantics must be well-defined for arrays. No "extension point" safety — any field can be overridden.
**Exploration:** quick
**Status:** captured

## D3: Array merge semantics

**Choice:** Name-keyed merge. Arrays of named objects (bindings, capabilities, workers, goals, milestones) merge by `name` field. Same name = override, new name = append. Removal deferred to `augment()` for v1; declarative `remove:` section tracked as engine#908.
**Alternatives:**
- Replace entire array — simpler but loses granularity
- Append only — no override capability, remove only via augment()
- `!name` prefix for removal — YAML `!` tag collision risk, no standard precedent across YAML merge systems
- Separate `remove:` section — explicit and collision-free, but deferred to avoid shipping a half-baked convention before demand materialises (engine#908)
**Rationale:** Every array element in the case definition schema has a `name` field. Name-keyed merge is the natural granularity. Override + add covers the common cases. Removal is rare (removing a base template binding entirely) and is cleanly handled by `augment()` until a declarative syntax is needed.
**Trade-offs:** Removal requires Java code in v1. Acceptable — `augment()` exists for exactly this purpose.
**Exploration:** quick
**Status:** captured

## D4: Epic scope — template ecosystem vs YAML overlay

**Choice:** Scope to YAML overlay/merge in engine + one extracted template as proof of concept. The "template ecosystem" framing was wrong — Maven + CDI already handle distribution and discovery. The ops-deployment story (casehub-ops#15) is a separate initiative.
**Alternatives:**
- Full template ecosystem — registry, discovery, 3 templates, documentation system
- Blueprint v1 — full declarative deployment manifest format
- Design the vision, build the foundation — spec the full architecture, build only overlay
**Rationale:** Brainstorming uncovered that casehub-ops-deployment already designs the declarative deployment surface (`casehub-deployment.yaml`, `DeploymentGoalLoader`, `DefinitionPayloadLoader`, drift detection). The only missing piece is YAML overlay/merge for case definitions. Building the overlay mechanism in engine enables the ops-deployment story without duplicating it.
**Trade-offs:** The ops-deployment end-to-end demo (ops#15) is not delivered by this epic. Template extraction beyond pr-review is follow-on work.
**Exploration:** deep-analysis
**Status:** captured

## D5: Merge logic location — module and class

**Choice:** Standalone `YamlMerger` utility class in `casehub-platform-api`. Called from `YamlCaseHub` before passing to `CaseDefinitionYamlMapper.load()`.
**Alternatives:**
- Inside `CaseDefinitionYamlMapper` — mapper takes on composition responsibility
- At `CaseDefinition` API model level — merge two parsed definitions programmatically
- In `engine-api` alongside the mapper — natural but limits reuse
**Rationale:** YAML deep merge is a generic structural operation used across the platform (case definitions, deployment manifests, agent profiles, compliance rules). Everything already depends on `platform-api`. `DeploymentGoalLoader.merge()` in ops could eventually delegate to it. JsonNode is the right merge level — structural, schema-agnostic.
**Trade-offs:** Platform-api gains a utility class. Acceptable — it's already the home for shared infrastructure.
**Exploration:** quick
**Depends on:** D4 (scope determines whether merge is needed at all)
**Status:** captured

## D6: Overlay specification API

**Choice:** Composite approach — three layers, all optional:
1. Two-arg constructor `super(base, overlay)` for explicit overlay path (immutable, no temporal coupling)
2. Classpath convention auto-discovers overlay (`-overrides` suffix in the same directory)
3. `augment()` runs last for Java-only modifications (unchanged)

Resolution order: base YAML → overlay YAML (explicit or convention) → augment().
**Alternatives:**
- `withOverlay()` method — temporal coupling risk (must call before first `getDefinition()`, silent if called after)
- Convention only — simpler API but no explicit control
**Rationale:** The two-arg constructor gives explicit control with immutable state. Convention provides zero-config convenience. `augment()` is the Java escape hatch. All three are optional — existing single-arg constructor is preserved, existing code is unaffected.
**Trade-offs:** Two discovery mechanisms (explicit + convention) could confuse. Convention path must be documented clearly.
**Revised:** R1-01 — replaced `withOverlay()` with constructor parameter to eliminate temporal coupling.
**Exploration:** quick
**Depends on:** D5 (merge logic must exist for overlay to work)
**Status:** captured

## D7: Convention path format

**Choice:** `-overrides` suffix in the same directory. Base `templates/pr-review.yaml` → convention looks for `templates/pr-review-overrides.yaml`.
**Alternatives:**
- `overrides/` prefix — base `templates/pr-review.yaml` → `overrides/templates/pr-review.yaml`. Works but requires knowing about a separate directory.
- `.override.yaml` extension — `templates/pr-review.override.yaml`. Less common in Java ecosystem.
**Rationale:** Follows common Java/YAML configuration conventions: Spring Boot (`application-{profile}.yaml`), Helm (`values-{env}.yaml`). Same directory, related files visible together, immediately understandable. No collision across JARs because filenames differ.
**Trade-offs:** If two templates share a filename in different directories (unlikely — names should be unique), their overrides are still distinguishable because the full path differs.
**Exploration:** quick
**Depends on:** D6 (convention is one of three overlay specification paths)
**Status:** captured

## D8: pr-review extraction location

**Choice:** Devtown submodule (`devtown/templates/pr-review/`). Standard Maven library extraction — move the YAML into a submodule that publishes its own artifact.
**Alternatives:**
- Engine submodule — close to overlay infrastructure but wrong ownership (engine is foundation, pr-review is application)
- Separate repo (`casehubio/templates`) — clean separation but overkill for a single proof-of-concept template
**Rationale:** The pr-review definition is devtown domain knowledge. Keeping it in devtown preserves ownership. Any app can add the Maven dependency and overlay it. If the template count grows beyond devtown, extract to a separate repo — that's a future decision, not a v1 concern.
**Trade-offs:** Maven coordinates are `io.casehub.devtown:...` which ties the artifact name to devtown. Acceptable for proof of concept; rename on extraction if needed.
**Exploration:** quick
**Depends on:** D4 (scope includes one extracted template as proof of concept), D6 (overlay mechanism must exist for the extraction to demonstrate composition)
**Status:** captured
