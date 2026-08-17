# Case Definition YAML Overlay/Merge for Declarative Deployment Composition

**Issue:** casehubio/devtown#187
**Date:** 2026-08-14
**Status:** Draft

## Problem

CaseHub case definitions are monolithic YAML files. When a reusable case definition ships as a Maven dependency, the consumer must either use it as-is or copy-and-modify the entire YAML. There is no way to declaratively override specific parts — a binding's escalation group, a goal condition, a routing strategy — without writing Java code.

### What already exists

The infrastructure for declarative deployment is largely in place:

- **Maven + CDI** — distribution (Maven JARs on classpath) and discovery (`Instance<CaseHub>` at startup) are solved
- **`casehub-ops-deployment`** — single declarative topology surface (`casehub-deployment.yaml`) with agents, channels, case types, trust policies, provider config, drift detection, and self-healing (casehubio/casehub-ops#7, implemented)
- **`DeploymentGoalLoader.merge()`** — merges deployment YAML fragments via array concatenation (not deep merge — different semantics from `YamlMerger`). Follow-up: refactor to delegate to `YamlMerger` for consistent merge behavior across the platform
- **`DefinitionPayloadLoader`** — loads case definition YAML files referenced by `definitionFile` in the deployment manifest
- **`YamlCaseHub.augment()`** — programmatic extension hook for Java worker functions

### The gap

YAML-level composition for case definitions. The `definitionFile` in the deployment manifest points to a single monolithic YAML. `YamlCaseHub` loads from a single classpath path. There is no overlay mechanism where a base definition is deep-merged with application-specific overrides.

## Scope

Three deliverables:

1. **`YamlMerger` utility in `casehub-platform-api`** — generic YAML deep merge with name-keyed array support
2. **Overlay support in `YamlCaseHub`** — base YAML + overlay YAML + augment(), three-layer resolution
3. **Proof of concept** — extract devtown's pr-review into a reusable Maven module with a working overlay example

### Out of scope

- Template registry/discovery — Maven + CDI already handle this
- ops-deployment end-to-end demo — tracked in casehubio/casehub-ops#15
- Declarative removal syntax — tracked in casehubio/engine#908
- Additional template extractions beyond pr-review
- Migrating devtown to the overlay model — incremental adoption, not big-bang

## YamlMerger

**Location:** `casehub-platform-api`, package `io.casehub.platform.api.yaml`

**API:**

```java
public final class YamlMerger {
    public static JsonNode merge(JsonNode base, JsonNode overlay);
    public static JsonNode merge(JsonNode base, JsonNode overlay, String keyField);
}
```

The two-arg overload defaults to `"name"` as the key field. The three-arg overload supports alternative keys (e.g. `"agentId"` for ops-deployment, `"type"` for other domains). Stateless utility. Jackson `databind` only — already a transitive dependency of `platform-api`.

### Merge semantics

| Node type | Behavior |
|-----------|----------|
| Object (map) | Recursive deep merge. Overlay keys override base keys. Base keys not in overlay are preserved. |
| Array of named objects | Name-keyed merge by `name` field. Same name = deep merge the element. New name = append. Base elements not in overlay are preserved. |
| Array without `name` fields | Overlay replaces the entire array. |
| Scalar | Overlay replaces base. |
| `null` value in overlay | Removes the key from the merged result (RFC 7396 JSON Merge Patch semantics for maps). |

### Name-keyed detection

An array is treated as name-keyed when the first element of either the base or overlay array is an object containing the key field. Detection checks the base array first; if the base array is empty, the overlay array is checked. This handles the edge case where a base has `bindings: []` and the overlay provides named elements. This matches all CaseHub case definition arrays (using the default `"name"` key):

- `bindings` — `name` field
- `capabilities` — `name` field
- `workers` — `name` field
- `goals` — `name` field
- `milestones` — `name` field
- `signals` — `name` field
- `labelRules` — `name` field

Arrays where neither base nor overlay first elements contain the key field (e.g. `layers`, `use.secrets`, `candidateGroups` string arrays) fall back to full replacement.

### Removal

v1 does not support declarative element removal from name-keyed arrays. Removal is handled by `augment()` in Java:

```java
definition.getBindings().removeIf(b -> b.getName().equals("unwanted"));
```

A declarative `remove:` section is tracked as casehubio/engine#908.

## YamlCaseHub overlay loading

### Changes to `YamlCaseHub`

`YamlCaseHub` gains:

- `overlayPath` field (final, nullable String) — set via new two-arg constructor
- `resolveOverlay()` private method — returns overlay `JsonNode` or null
- Updated `getDefinition()` — loads base as `JsonNode`, resolves overlay, merges via `YamlMerger`, passes merged `JsonNode` to `CaseDefinitionYamlMapper.load()`

```java
public class YamlCaseHub extends CaseHub {

    private final String path;
    private final String overlayPath;
    private volatile CaseDefinition definition;

    public YamlCaseHub(String path) {
        this(path, null);
    }

    public YamlCaseHub(String path, String overlayPath) {
        this.path = path;
        this.overlayPath = overlayPath;
    }

    @Override
    public final CaseDefinition getDefinition() {
        if (definition == null) {
            synchronized (this) {
                if (definition == null) {
                    JsonNode base = loadYamlAsJsonNode(path);
                    JsonNode overlay = resolveOverlay();
                    JsonNode merged = (overlay != null)
                        ? YamlMerger.merge(base, overlay)
                        : base;
                    CaseDefinition loaded = CaseDefinitionYamlMapper.load(
                        merged, objectMapper, expressionEngineRegistry,
                        workerFunctionProviderRegistry);
                    augment(loaded);
                    definition = loaded;
                }
            }
        }
        return definition;
    }

    private JsonNode resolveOverlay() {
        // 1. Explicit path
        if (overlayPath != null) {
            return loadYamlAsJsonNode(overlayPath);  // throws if not found
        }
        // 2. Convention: base-overrides.yaml
        String conventionPath = deriveConventionPath(path);
        InputStream is = Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(conventionPath);
        if (is != null) {
            return loadYamlAsJsonNode(is);
        }
        return null;  // no overlay
    }

    private static String deriveConventionPath(String basePath) {
        int dot = basePath.lastIndexOf('.');
        if (dot < 0) return basePath + "-overrides";
        return basePath.substring(0, dot) + "-overrides" + basePath.substring(dot);
    }

    // ...
}
```

**Note on classpath convention:** The automatic convention path (`-overrides` suffix) uses classpath resource lookup. In a multi-JAR deployment, any JAR containing a file matching the convention name will activate as an overlay. This is acceptable for CaseHub's tightly controlled classpath (internal Maven deps). Template modules should document their convention paths so consumers know what filenames to avoid.

### Overlay resolution order

First match wins:

1. **Explicit:** two-arg constructor `super(base, overlay)` — classpath resource, throws if not found
2. **Convention:** derive from base path by inserting `-overrides` before the extension. `templates/pr-review.yaml` → `templates/pr-review-overrides.yaml`. Classpath lookup — if not found, no overlay applied (silent).

### Resolution layers

Three layers, applied in order:

1. **Base YAML** — loaded from the classpath path passed to the constructor
2. **Overlay YAML** — deep-merged on top of the base via `YamlMerger`
3. **`augment()`** — Java-level modifications on the merged `CaseDefinition`

### New overload on CaseDefinitionYamlMapper

```java
public static CaseDefinition load(
    JsonNode mergedNode,
    ObjectMapper objectMapper,
    ExpressionEngineRegistry registry,
    WorkerFunctionProviderRegistry providerRegistry);
```

Accepts pre-merged `JsonNode` directly, avoiding double-parse. Internally creates a lenient copy of the `ObjectMapper` (disabling `FAIL_ON_UNKNOWN_PROPERTIES`, installing `UnknownPropertyWarningHandler`) — replicating the same lenient deserialization as the existing `load(InputStream)` overload. Then converts the `JsonNode` to the generated schema class via `lenient.treeToValue()` and delegates to the existing `convertToApiModel()`. The raw `JsonNode` is passed through as-is for free-form fields (`semanticData`, etc.). The lenient ObjectMapper creation should be extracted into a shared private method used by both overloads to avoid divergence.

### Backward compatibility

Fully backward-compatible:

- Existing `YamlCaseHub` subclasses using the single-arg constructor and having no convention file on the classpath get identical behavior
- `augment()` contract is unchanged
- `getDefinition()` remains `final`
- `CaseDefinitionYamlMapper.load(InputStream, ...)` overload is unchanged

## Proof of concept — pr-review extraction

### New devtown submodule

**Directory:** `devtown/templates/pr-review/`
**Maven coordinates:** `io.casehub.devtown:casehub-devtown-pr-review-template`

**Contents:**

- `src/main/resources/templates/pr-review.yaml` — base case definition extracted from `devtown/review/src/main/resources/devtown/pr-review.yaml`. Identical content, `templates/` classpath prefix signals reusability.
- `src/main/java/.../PrReviewTemplateCaseHub.java` — abstract base class:

```java
public abstract class PrReviewTemplateCaseHub extends YamlCaseHub {
    protected PrReviewTemplateCaseHub() {
        super("templates/pr-review.yaml");
    }

    protected PrReviewTemplateCaseHub(String overlayPath) {
        super("templates/pr-review.yaml", overlayPath);
    }
}
```

Abstract because the template does not provide the `merge-executor` worker function — that requires `MergeClient`, which is app-specific domain logic. Two constructors: no-overlay (convention discovery applies) and explicit overlay path.

### Consumer usage

In devtown `app/` module:

```java
@ApplicationScoped
public class PrReviewCaseHub extends PrReviewTemplateCaseHub {
    @Inject MergeClient mergeClient;

    public PrReviewCaseHub() {
        super("devtown/pr-review-overrides.yaml");
    }

    @Override
    protected void augment(CaseDefinition definition) {
        definition.getWorkers().add(Worker.builder()
            .name("merge-executor")
            .capabilityName("merge-executor")
            .function(this::adaptMerge)
            .build());
    }

    WorkerResult adaptMerge(Map<String, Object> input) {
        // ... domain logic unchanged
    }
}
```

### Override YAML

At `devtown/app/src/main/resources/devtown/pr-review-overrides.yaml`:

```yaml
spec:
  bindings:
    - name: human-approval
      humanTask:
        candidateGroups: [devtown-reviewers]
        expiresIn: PT48H
```

This overrides only the human-approval binding's candidate groups and expiry. Everything else comes from the base template.

### What this proves

A second application (not devtown) can add the template Maven dependency, extend `PrReviewTemplateCaseHub`, provide their own overlay YAML and `MergeClient` implementation, and have a working PR review case without writing 438 lines of YAML.

## Ops integration

**Not in this epic** — tracked in casehubio/casehub-ops#15.

When ops-deployment loads a case definition via `definitionFile`, it currently resolves a single YAML file. The overlay mechanism enables a natural extension: `CaseTypeNodeSpec` gains an `overlayFile` field alongside `definitionFile`. `DefinitionPayloadLoader` loads both, merges via `YamlMerger`, and passes the merged payload to the compiler. The `YamlMerger` in `platform-api` is designed to support this.

## Cross-repo sequencing

| Step | Repo | What | Depends on |
|------|------|------|------------|
| 1 | `casehub-platform-api` | Add `YamlMerger` utility. Release SNAPSHOT. | — |
| 2 | `casehub-engine` | Add `load(JsonNode)` overload to `CaseDefinitionYamlMapper`. Update `YamlCaseHub` with overlay loading. | Step 1 |
| 3 | `casehub-devtown` | Extract pr-review into `templates/pr-review/` submodule. Update `app/` to use template + overlay. | Step 2 |

Steps 2 and 3 are independent of each other — engine overlay support can be tested with inline test YAMLs before the devtown extraction lands.

## Testing strategy

### YamlMerger (platform-api)

Pure unit tests. No CDI, no Quarkus. JsonNode in, JsonNode out.

- Map deep merge — overlay keys override, base keys preserved
- Name-keyed array merge — override by name, append new, preserve unmentioned
- Non-named array replacement
- Null value removal (RFC 7396)
- Nested merge — deep objects within array elements
- Edge cases: empty overlay, empty base, overlay with no `spec` block

### YamlCaseHub overlay (engine)

- Unit tests with `CaseDefinitionYamlMapper.load(JsonNode)` using crafted base + overlay JsonNodes
- Convention path derivation — verify `-overrides` suffix insertion
- Explicit overlay via two-arg constructor — verify classpath loading
- Convention discovery — verify silent skip when no override file exists
- One `@QuarkusTest` for CDI integration (ObjectMapper, ExpressionEngineRegistry injection with overlay)

### Devtown extraction (devtown)

- Existing `PrReviewCaseHubTest` adapted to use the template + overlay path
- Verify merged definition matches the original monolithic definition — same capabilities, bindings, goals, milestones, completion
- Verify overlay customisation is applied — different candidateGroups, different expiresIn

## References

- casehubio/devtown#187 — this epic
- casehubio/casehub-ops#7 — deployment app-level topology (implemented)
- casehubio/casehub-ops#15 — end-to-end deployment demo (depends on this)
- casehubio/engine#908 — declarative removal syntax (follow-up)
- `docs/gastown-casehub-analysis-v6.md` §8 — composability comparison
- Decisions: `decisions.md` in this spec directory (D1–D8)

## Review changelog (revision 1)

| # | Change | Source |
|---|--------|--------|
| R1-01 | `withOverlay()` replaced with two-arg constructor — eliminates temporal coupling | Structure review |
| R1-02 | `load(JsonNode)` overload explicitly uses lenient deserialization; shared private method | Structure review |
| R1-03 | `YamlMerger.merge()` gains configurable key field parameter; default `"name"` | Structure review |
| R1-04 | `DeploymentGoalLoader.merge()` documented as array concatenation, not deep merge | Structure review |
| R1-05 | Name-keyed detection checks base then overlay; empty array handling documented | Structure review |
| R1-06 | Classpath convention shadowing risk acknowledged; documentation note added | Structure review |
