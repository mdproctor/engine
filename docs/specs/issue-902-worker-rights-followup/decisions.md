## D1: EngineWorkerActions placement

**Choice:** engine-api — public vocabulary class alongside `Binding` which references `WorkerAction`
**Alternatives:**
- engine-common — too internal; YAML parsing in engine-api needs the lookup map
- engine runtime — hidden from API consumers; Binding is in engine-api
**Rationale:** `Binding` lives in engine-api and carries `List<WorkerAction>`. The constants class that defines engine-specific action vocabulary belongs at the same level. The YAML mapper (also in engine-api) needs the kebab-case lookup.
**Trade-offs:** None significant — this is the natural placement.
**Exploration:** quick
**Status:** captured

## D2: EngineAuthorizationContext placement

**Choice:** engine-api — record implementing `WorkerAuthorizationContext` marker interface
**Alternatives:**
- engine-common — context carries `caseDefinitionId` which is an engine API concept
**Rationale:** The context is part of the engine's public contract for worker authorization. Policies in runtime/ downcast it. Placing in engine-api makes it available to both.
**Trade-offs:** None — single record, minimal surface.
**Exploration:** quick
**Status:** captured

## D3: CaseScopeExtractor placement

**Choice:** engine-rest — implements platform's `WorkerScopeExtractor` SPI
**Alternatives:**
- engine-common — scope extraction is a REST concern (parses URL paths)
**Rationale:** The extractor parses JAX-RS URL paths (`cases/{uuid}`) to return `ResourceId`. This is a REST filter concern, belongs with the module that adds `acl-worker` as a dependency.
**Trade-offs:** None.
**Exploration:** quick
**Status:** captured

## D4: YAML permissionIntent parsing — fix missing conversion

**Choice:** Add conversion to `CaseDefinitionYamlMapper.convertBinding()` using `EngineWorkerActions.fromKebabCase()`
**Alternatives:**
- Leave it broken (permissionIntent always null, defaults to READ_CONTEXT) — masks YAML author intent
- Custom Jackson deserializer on WorkerAction — can't do; WorkerAction is in platform-api (zero deps)
**Rationale:** The YAML schema declares permissionIntent as `List<String>` (kebab-case), the generated `io.casehub.model.Binding` stores it as `List<String>`, but the mapper never converts it to the API model's `List<WorkerAction>`. This is a pre-existing gap — every YAML-specified permission intent is silently ignored. The lookup map in `EngineWorkerActions` provides clean conversion.
**Trade-offs:** Invalid kebab-case names now throw at parse time instead of being silently ignored.
**Depends on:** D1 (EngineWorkerActions placement)
**Exploration:** quick
**Status:** captured
